package parser

import (
	"fmt"
	"io"
	"log"
	"math"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/golang/geo/r3"

	demoinfocs "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs"
	common "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs/common"
	events "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs/events"
	msg "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs/msg"

	"github.com/countatic/demo-parser/internal/models"
)

// defaultTickRate é usado quando a demo não expõe o tick rate.
// Servidores oficiais da Valve rodam a 64 tick.
const defaultTickRate = 64

// DemoParser encapsula a lógica de parsing de arquivos .dem do CS2.
// É thread-safe: cada chamada a Parse cria seu próprio parser interno.
type DemoParser struct{}

// NewDemoParser cria uma nova instância do parser.
func NewDemoParser() *DemoParser {
	return &DemoParser{}
}

// persistWeaponFire controla a emissão de eventos WEAPON_FIRE.
//
// São ~10-20 mil por partida — necessários para calcular crosshair placement,
// mas responsáveis pela maior parte do volume do JSON e das linhas no banco.
// Defina PERSIST_WEAPON_FIRE=false para desligar.
func persistWeaponFire() bool {
	return os.Getenv("PERSIST_WEAPON_FIRE") != "false"
}

// Parse lê um arquivo .dem a partir de um io.Reader e extrai todos os
// eventos relevantes, retornando a estrutura ParsedDemo pronta para
// serialização em JSON.
//
// Esta função é segura para chamadas concorrentes — cada invocação cria
// um parser independente do demoinfocs-golang.
func (dp *DemoParser) Parse(reader io.Reader) (result *models.ParsedDemo, err error) {
	// O demoinfocs entra em PANIC (não devolve erro) com entrada inválida:
	// `NewParser` sobre um stream vazio ou truncado estoura em gobitread, e o
	// parsing de demos corrompidas panica dentro de sendtables2. Sem este
	// recover, um único upload malformado derrubaria o processo inteiro do
	// serviço Go, junto com qualquer outra demo sendo parseada em paralelo.
	defer func() {
		if r := recover(); r != nil {
			result = nil
			err = fmt.Errorf("demo inválida ou corrompida: %v", r)
			log.Printf("Panic recuperado durante o parsing: %v", r)
		}
	}()

	p := demoinfocs.NewParser(reader)
	defer p.Close()

	result = &models.ParsedDemo{}

	// Mutex para proteger a escrita concorrente na lista de eventos do round
	// atual. O demoinfocs dispara handlers de forma síncrona, mas o mutex
	// protege caso a API interna mude.
	var mu sync.Mutex

	var currentRound *models.ParsedRound
	roundNumber := 0
	lastTick := 0

	emitWeaponFire := persistWeaponFire()

	// tick devolve o tick REAL do jogo.
	//
	// Antes usávamos p.CurrentFrame(), mas em demos Source 2 (CS2) frame != tick:
	// o frame é o índice do pacote no arquivo, não o tempo de jogo. Todo cálculo
	// temporal (duração de round, janela de trade kill) saía errado.
	tick := func() int {
		t := p.GameState().IngameTick()
		if t > lastTick {
			lastTick = t
		}
		return t
	}

	// ─── Metadados do servidor ───────────────────────────────────────
	// A v5 removeu Parser.ParseHeader()/Header() da interface pública — o
	// header virou estado interno. O nome do mapa passa a vir do net message
	// CSVCMsg_ServerInfo, que o servidor envia no início da transmissão.
	p.RegisterNetMessageHandler(func(m *msg.CSVCMsg_ServerInfo) {
		mu.Lock()
		defer mu.Unlock()

		if name := m.GetMapName(); name != "" {
			result.MapName = name
		}
	})

	// O demo de Source 2 não carrega a data da partida. O Java sobrescreve
	// com o `matchtime` do Game Coordinator quando disponível; aqui fica
	// apenas o fallback.
	result.PlayedAt = time.Now().UTC().Format(time.RFC3339)

	// ─── Evento: Início de Round ─────────────────────────────────────
	p.RegisterEventHandler(func(e events.RoundStart) {
		mu.Lock()
		defer mu.Unlock()

		// Fecha o round anterior só agora, e não no RoundEnd. Ver a
		// explicação no handler de RoundEnd.
		if currentRound != nil {
			result.Rounds = append(result.Rounds, *currentRound)
		}

		roundNumber++
		currentRound = &models.ParsedRound{
			RoundNumber: roundNumber,
			StartTick:   tick(),
			Events:      make([]models.ParsedEvent, 0),
		}
	})

	// ─── Evento: Fim de Round ────────────────────────────────────────
	p.RegisterEventHandler(func(e events.RoundEnd) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}

		currentRound.EndTick = tick()
		currentRound.WinnerSide = teamToString(common.Team(e.Winner))
		currentRound.EndReason = roundEndReasonToString(e.Reason)

		// A duração fica para o pós-processamento: o tick rate só é
		// confiável depois do ParseToEnd em demos Source 2.

		gs := p.GameState()
		currentRound.CTScoreAfter = gs.TeamCounterTerrorists().Score()
		currentRound.TRScoreAfter = gs.TeamTerrorists().Score()

		// IMPORTANTE: o round NÃO é fechado aqui.
		//
		// No CS2 os jogadores continuam atirando por alguns segundos depois do
		// round terminar oficialmente, e essas kills contam no placar da Valve.
		// Zerar `currentRound` neste ponto fazia todo evento dessa janela ser
		// descartado — a demo somava 13/15 para um jogador que o Game
		// Coordinator reportava como 14/18.
		//
		// O round é fechado no próximo RoundStart (ou no fim do parsing),
		// de modo que os eventos pós-round caem no round a que pertencem.
	})

	// ─── Evento: Kill ────────────────────────────────────────────────
	p.RegisterEventHandler(func(e events.Kill) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}

		event := models.ParsedEvent{
			EventType:  "KILL",
			Tick:       tick(),
			IsHeadshot: boolPtr(e.IsHeadshot),
		}

		if e.Killer != nil {
			event.ActorSteamID = steamIDToString(e.Killer)
			event.ActorName = e.Killer.Name
			event.ActorSide = teamToString(e.Killer.Team)
			event.ActorPositionX = float64Ptr(e.Killer.Position().X)
			event.ActorPositionY = float64Ptr(e.Killer.Position().Y)
			event.ActorPositionZ = float64Ptr(e.Killer.Position().Z)
			event.ViewAngleX = float64Ptr(float64(e.Killer.ViewDirectionX()))
			event.ViewAngleY = float64Ptr(float64(e.Killer.ViewDirectionY()))
		}

		if e.Victim != nil {
			event.VictimSteamID = steamIDToString(e.Victim)
			event.VictimName = e.Victim.Name
			event.VictimSide = teamToString(e.Victim.Team)
			event.VictimPositionX = float64Ptr(e.Victim.Position().X)
			event.VictimPositionY = float64Ptr(e.Victim.Position().Y)
			event.VictimPositionZ = float64Ptr(e.Victim.Position().Z)
		}

		if e.Assister != nil {
			event.AssisterSteamID = steamIDToString(e.Assister)
			event.AssisterName = e.Assister.Name
		}

		// weaponToID e não Weapon.String(): ver o comentário em weaponToID.
		event.Weapon = weaponToID(e.Weapon)

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Evento: PlayerHurt (dano) ───────────────────────────────────
	// Sem este handler TODA métrica de dano do Java fica zerada — utility
	// damage, ADR, etc. Nada emitia eventos DAMAGE antes.
	p.RegisterEventHandler(func(e events.PlayerHurt) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil || e.Attacker == nil || e.Player == nil {
			return
		}

		event := models.ParsedEvent{
			EventType: "DAMAGE",
			Tick:      tick(),
			// HealthDamageTaken exclui o excedente: alvo com 5 de vida que leva
			// 100 conta 5. É o número correto para ADR.
			DamageAmount: intPtr(e.HealthDamageTaken),
			DamageArmor:  intPtr(e.ArmorDamageTaken),
			Weapon:       weaponToID(e.Weapon),
		}

		event.ActorSteamID = steamIDToString(e.Attacker)
		event.ActorName = e.Attacker.Name
		event.ActorSide = teamToString(e.Attacker.Team)
		event.ActorPositionX = float64Ptr(e.Attacker.Position().X)
		event.ActorPositionY = float64Ptr(e.Attacker.Position().Y)
		event.ActorPositionZ = float64Ptr(e.Attacker.Position().Z)

		event.VictimSteamID = steamIDToString(e.Player)
		event.VictimName = e.Player.Name
		event.VictimSide = teamToString(e.Player.Team)
		event.VictimPositionX = float64Ptr(e.Player.Position().X)
		event.VictimPositionY = float64Ptr(e.Player.Position().Y)
		event.VictimPositionZ = float64Ptr(e.Player.Position().Z)

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Evento: Granada arremessada ─────────────────────────────────
	// Conta a granada no momento do ARREMESSO. Sem isto, as métricas de
	// smokes/HE/molotovs por round ficavam permanentemente em zero.
	p.RegisterEventHandler(func(e events.GrenadeProjectileThrow) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil || e.Projectile == nil || e.Projectile.Thrower == nil {
			return
		}

		eventType := grenadeEventType(e.Projectile.WeaponInstance)
		if eventType == "" {
			// Flashbang vem de FlashExplode; decoy não alimenta métrica alguma.
			return
		}

		thrower := e.Projectile.Thrower
		pos := e.Projectile.Position()

		currentRound.Events = append(currentRound.Events, models.ParsedEvent{
			EventType:      eventType,
			Tick:           tick(),
			ActorSteamID:   steamIDToString(thrower),
			ActorName:      thrower.Name,
			ActorSide:      teamToString(thrower.Team),
			ActorPositionX: float64Ptr(pos.X),
			ActorPositionY: float64Ptr(pos.Y),
			ActorPositionZ: float64Ptr(pos.Z),
			Weapon:         weaponToID(e.Projectile.WeaponInstance),
		})
	})

	// ─── Evento: Disparo ─────────────────────────────────────────────
	// Base para crosshair placement: posição e ângulo de visão no instante
	// em que o jogador atirou.
	if emitWeaponFire {
		p.RegisterEventHandler(func(e events.WeaponFire) {
			mu.Lock()
			defer mu.Unlock()

			if currentRound == nil || e.Shooter == nil || e.Weapon == nil {
				return
			}

			// Granadas e faca não dizem nada sobre mira.
			if !isAimWeapon(e.Weapon.Type) {
				return
			}

			// Posição dos OLHOS, não dos pés: o ângulo até a cabeça do inimigo
			// só faz sentido a partir de onde a mira realmente está.
			olhos, ok := e.Shooter.PositionEyes()
			if !ok {
				olhos = e.Shooter.Position()
			}

			event := models.ParsedEvent{
				EventType:      "WEAPON_FIRE",
				Tick:           tick(),
				ActorSteamID:   steamIDToString(e.Shooter),
				ActorName:      e.Shooter.Name,
				ActorSide:      teamToString(e.Shooter.Team),
				ActorPositionX: float64Ptr(olhos.X),
				ActorPositionY: float64Ptr(olhos.Y),
				ActorPositionZ: float64Ptr(olhos.Z),
				ViewAngleX:     float64Ptr(float64(e.Shooter.ViewDirectionX())),
				ViewAngleY:     float64Ptr(float64(e.Shooter.ViewDirectionY())),
				Weapon:         weaponToID(e.Weapon),
			}

			// Anexa a cabeça do inimigo mais próximo do centro da mira.
			//
			// Sem isto o crosshair placement é incalculável: o Java só recebe o
			// ângulo de visão absoluto, e medir "o pitch estava perto de zero"
			// avalia se o jogador olhava para o horizonte — não onde o inimigo
			// estava. Só o Go tem o estado completo do jogo para localizar os
			// inimigos vivos no instante do disparo.
			if alvo, achou := inimigoMaisProximoDaMira(p, e.Shooter, olhos); achou {
				event.VictimSteamID = steamIDToString(alvo.jogador)
				event.VictimName = alvo.jogador.Name
				event.VictimSide = teamToString(alvo.jogador.Team)
				event.VictimPositionX = float64Ptr(alvo.cabeca.X)
				event.VictimPositionY = float64Ptr(alvo.cabeca.Y)
				event.VictimPositionZ = float64Ptr(alvo.cabeca.Z)
			}

			currentRound.Events = append(currentRound.Events, event)
		})
	}

	// ─── Evento: MVP do round ────────────────────────────────────────
	p.RegisterEventHandler(func(e events.RoundMVPAnnouncement) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil || e.Player == nil {
			return
		}

		currentRound.Events = append(currentRound.Events, models.ParsedEvent{
			EventType:    "MVP",
			Tick:         tick(),
			ActorSteamID: steamIDToString(e.Player),
			ActorName:    e.Player.Name,
			ActorSide:    teamToString(e.Player.Team),
		})
	})

	// ─── Evento: FlashExplode ────────────────────────────────────────
	// A flash é contabilizada quando ESTOURA, não no arremesso. Mudar isso
	// alteraria o denominador de flashEfficiency (passaria a incluir flashes
	// jogadas no fim do round que nunca detonam) e faria todas as métricas
	// históricas mudarem de valor silenciosamente.
	p.RegisterEventHandler(func(e events.FlashExplode) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}

		event := models.ParsedEvent{
			EventType: "FLASH_THROWN",
			Tick:      tick(),
			Weapon:    "flashbang",
		}

		if e.Thrower != nil {
			event.ActorSteamID = steamIDToString(e.Thrower)
			event.ActorName = e.Thrower.Name
			event.ActorSide = teamToString(e.Thrower.Team)
		}

		event.ActorPositionX = float64Ptr(e.Position.X)
		event.ActorPositionY = float64Ptr(e.Position.Y)
		event.ActorPositionZ = float64Ptr(e.Position.Z)

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Evento: PlayerFlashed ───────────────────────────────────────
	p.RegisterEventHandler(func(e events.PlayerFlashed) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}

		event := models.ParsedEvent{
			EventType: "FLASH_BLINDED",
			Tick:      tick(),
			Weapon:    "flashbang",
		}

		if e.Attacker != nil {
			event.ActorSteamID = steamIDToString(e.Attacker)
			event.ActorName = e.Attacker.Name
			event.ActorSide = teamToString(e.Attacker.Team)
		}

		if e.Player != nil {
			event.VictimSteamID = steamIDToString(e.Player)
			event.VictimName = e.Player.Name
			event.VictimSide = teamToString(e.Player.Team)

			if e.Attacker != nil {
				isEnemy := e.Attacker.Team != e.Player.Team
				event.IsEnemyFlash = boolPtr(isEnemy)
			}
		}

		event.FlashDurationSecs = float64Ptr(e.FlashDuration().Seconds())

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Evento: BombPlanted ─────────────────────────────────────────
	p.RegisterEventHandler(func(e events.BombPlanted) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}
		currentRound.BombPlanted = true

		event := models.ParsedEvent{
			EventType: "BOMB_PLANTED",
			Tick:      tick(),
		}

		if e.Player != nil {
			event.ActorSteamID = steamIDToString(e.Player)
			event.ActorName = e.Player.Name
			event.ActorSide = teamToString(e.Player.Team)
		}

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Evento: BombDefused ─────────────────────────────────────────
	p.RegisterEventHandler(func(e events.BombDefused) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}
		currentRound.BombDefused = true

		event := models.ParsedEvent{
			EventType: "BOMB_DEFUSED",
			Tick:      tick(),
		}

		if e.Player != nil {
			event.ActorSteamID = steamIDToString(e.Player)
			event.ActorName = e.Player.Name
			event.ActorSide = teamToString(e.Player.Team)
		}

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Parsear até o fim ────────────────────────────────────────────
	log.Println("Iniciando parsing tick a tick da demo...")
	if err := p.ParseToEnd(); err != nil {
		return nil, fmt.Errorf("erro durante o parsing da demo: %w", err)
	}

	// ─── Pós-processamento ────────────────────────────────────────────

	// Fecha o último round: como os rounds passaram a ser fechados no
	// RoundStart seguinte, o último da partida nunca teria um.
	mu.Lock()
	if currentRound != nil {
		result.Rounds = append(result.Rounds, *currentRound)
		currentRound = nil
	}
	mu.Unlock()

	// O tick rate SÓ é confiável depois do parse completo em demos Source 2.
	// Lê-lo antes do ParseToEnd devolvia 0, o que zerava Match.tickRate e a
	// duração de todos os rounds.
	result.TickRate = int(p.TickRate())
	if result.TickRate <= 0 {
		log.Printf("Tick rate indisponivel na demo; assumindo %d.", defaultTickRate)
		result.TickRate = defaultTickRate
	}

	for i := range result.Rounds {
		d := result.Rounds[i].EndTick - result.Rounds[i].StartTick
		if d > 0 {
			result.Rounds[i].DurationSeconds = float64(d) / float64(result.TickRate)
		}
	}

	result.DurationSeconds = lastTick / result.TickRate

	// mapName é NOT NULL no banco: valor vazio derruba a transação inteira.
	if result.MapName == "" {
		log.Println("Nome do mapa nao encontrado na demo; usando 'unknown'.")
		result.MapName = "unknown"
	}

	result.TotalRounds = len(result.Rounds)
	if result.TotalRounds > 0 {
		lastRound := result.Rounds[result.TotalRounds-1]
		result.ScoreCT = lastRound.CTScoreAfter
		result.ScoreTR = lastRound.TRScoreAfter
	}

	totalEvents := 0
	for _, r := range result.Rounds {
		totalEvents += len(r.Events)
	}

	log.Printf("Parsing concluido: mapa=%s, rounds=%d, placar=%d-%d, tickRate=%d, eventos=%d",
		result.MapName, result.TotalRounds, result.ScoreCT, result.ScoreTR,
		result.TickRate, totalEvents)

	return result, nil
}

// ═══════════════════════════════════════════════════════════════════
//  CROSSHAIR PLACEMENT
// ═══════════════════════════════════════════════════════════════════

// coneMaximoGraus limita quais disparos entram na métrica de mira.
//
// Um tiro com o inimigo mais próximo a 80° do centro da tela não diz nada
// sobre posicionamento de mira — é spray às cegas, tiro em parede ou
// utilitária. Restringir a um cone frontal mantém a métrica falando sobre
// duelos reais.
const coneMaximoGraus = 45.0

type alvoNaMira struct {
	jogador *common.Player
	cabeca  r3.Vector
	angulo  float64
}

// inimigoMaisProximoDaMira localiza, entre os inimigos vivos, aquele cuja
// cabeça está mais perto do centro da mira no instante do disparo.
//
// Escolher o de MENOR erro angular é proposital: assume-se que o jogador
// mirava em alguém, e esse alguém é o candidato mais plausível.
func inimigoMaisProximoDaMira(p demoinfocs.Parser, atirador *common.Player,
	olhos r3.Vector) (alvoNaMira, bool) {

	mira := vetorDaMira(float64(atirador.ViewDirectionX()), float64(atirador.ViewDirectionY()))

	melhor := alvoNaMira{angulo: math.MaxFloat64}
	achou := false

	for _, outro := range p.GameState().Participants().Playing() {
		if outro == nil || outro == atirador || !outro.IsAlive() {
			continue
		}
		if outro.Team == atirador.Team {
			continue
		}

		cabeca, ok := outro.PositionEyes()
		if !ok {
			continue
		}

		direcao := r3.Vector{
			X: cabeca.X - olhos.X,
			Y: cabeca.Y - olhos.Y,
			Z: cabeca.Z - olhos.Z,
		}

		norma := direcao.Norm()
		if norma == 0 {
			continue
		}

		cos := (mira.X*direcao.X + mira.Y*direcao.Y + mira.Z*direcao.Z) / norma
		// Clamp: erro de ponto flutuante pode empurrar para fora de [-1,1]
		// e fazer Acos devolver NaN.
		if cos > 1 {
			cos = 1
		} else if cos < -1 {
			cos = -1
		}

		angulo := math.Acos(cos) * 180 / math.Pi
		if angulo < melhor.angulo {
			melhor = alvoNaMira{jogador: outro, cabeca: cabeca, angulo: angulo}
			achou = true
		}
	}

	if !achou || melhor.angulo > coneMaximoGraus {
		return alvoNaMira{}, false
	}
	return melhor, true
}

// vetorDaMira converte os ângulos de visão da Source num vetor unitário.
//
// Convenção da engine: yaw 0° aponta para +X e cresce no sentido
// anti-horário; pitch é POSITIVO olhando para BAIXO — daí o sinal negativo
// no eixo Z.
func vetorDaMira(yawGraus, pitchGraus float64) r3.Vector {
	yaw := yawGraus * math.Pi / 180
	pitch := pitchGraus * math.Pi / 180

	return r3.Vector{
		X: math.Cos(pitch) * math.Cos(yaw),
		Y: math.Cos(pitch) * math.Sin(yaw),
		Z: -math.Sin(pitch),
	}
}

// ═══════════════════════════════════════════════════════════════════
//  UTILITÁRIOS DE CONVERSÃO
// ═══════════════════════════════════════════════════════════════════

// steamIDToString converte o SteamID de um jogador para SteamID64 (17 dígitos).
func steamIDToString(player *common.Player) string {
	return strconv.FormatUint(player.SteamID64, 10)
}

// teamToString converte a enum Team do demoinfocs para as strings do Java.
func teamToString(team common.Team) string {
	switch team {
	case common.TeamCounterTerrorists:
		return "CT"
	case common.TeamTerrorists:
		return "TR"
	default:
		return ""
	}
}

// weaponToID devolve o identificador INTERNO da arma (ex: "ak47", "hegrenade").
//
// Por que não usar Equipment.String(): esse método devolve o nome de exibição
// ("AK-47", "HE Grenade", "Molotov"), mas o Java casa contra ids internos —
// UtilityStatStrategy.DAMAGE_UTILITIES procura "hegrenade", "inferno",
// "molotov", "incgrenade". Com o nome de exibição nenhum match acontece e o
// dano de utilitária fica zerado mesmo com os eventos DAMAGE presentes.
func weaponToID(eq *common.Equipment) string {
	if eq == nil {
		return ""
	}

	switch eq.Type {
	// ─── Pistolas ───
	case common.EqP2000:
		return "hkp2000"
	case common.EqGlock:
		return "glock"
	case common.EqP250:
		return "p250"
	case common.EqDeagle:
		return "deagle"
	case common.EqFiveSeven:
		return "fiveseven"
	case common.EqDualBerettas:
		return "elite"
	case common.EqTec9:
		return "tec9"
	case common.EqCZ:
		return "cz75a"
	case common.EqUSP:
		return "usp_silencer"
	case common.EqRevolver:
		return "revolver"

	// ─── SMGs ───
	case common.EqMP7:
		return "mp7"
	case common.EqMP9:
		return "mp9"
	case common.EqBizon:
		return "bizon"
	case common.EqMac10:
		return "mac10"
	case common.EqUMP:
		return "ump45"
	case common.EqP90:
		return "p90"
	case common.EqMP5:
		return "mp5sd"

	// ─── Shotguns / LMGs ───
	case common.EqSawedOff:
		return "sawedoff"
	case common.EqNova:
		return "nova"
	case common.EqSwag7:
		return "mag7"
	case common.EqXM1014:
		return "xm1014"
	case common.EqM249:
		return "m249"
	case common.EqNegev:
		return "negev"

	// ─── Rifles ───
	case common.EqGalil:
		return "galilar"
	case common.EqFamas:
		return "famas"
	case common.EqAK47:
		return "ak47"
	case common.EqM4A4:
		return "m4a1"
	case common.EqM4A1:
		return "m4a1_silencer"
	case common.EqSSG08:
		return "ssg08"
	case common.EqSG553:
		return "sg556"
	case common.EqAUG:
		return "aug"
	case common.EqAWP:
		return "awp"
	case common.EqScar20:
		return "scar20"
	case common.EqG3SG1:
		return "g3sg1"

	// ─── Utilitárias (casam com DAMAGE_UTILITIES no Java) ───
	case common.EqDecoy:
		return "decoy"
	case common.EqMolotov:
		return "molotov"
	case common.EqIncendiary:
		return "incgrenade"
	case common.EqFlash:
		return "flashbang"
	case common.EqSmoke:
		return "smokegrenade"
	case common.EqHE:
		return "hegrenade"

	// ─── Outros ───
	case common.EqZeus:
		return "taser"
	case common.EqKnife:
		return "knife"
	case common.EqBomb:
		return "c4"
	case common.EqWorld:
		return "world"
	default:
		return ""
	}
}

// grenadeEventType mapeia a granada arremessada para o EventType do Java.
// Devolve "" para granadas que não geram evento de arremesso próprio.
func grenadeEventType(eq *common.Equipment) string {
	if eq == nil {
		return ""
	}

	switch eq.Type {
	case common.EqSmoke:
		return "SMOKE_THROWN"
	case common.EqHE:
		return "HE_THROWN"
	case common.EqMolotov, common.EqIncendiary:
		return "MOLOTOV_THROWN"
	default:
		return ""
	}
}

// isAimWeapon informa se a arma é relevante para métricas de mira.
func isAimWeapon(t common.EquipmentType) bool {
	switch t {
	case common.EqUnknown, common.EqKnife, common.EqBomb, common.EqZeus,
		common.EqDecoy, common.EqMolotov, common.EqIncendiary, common.EqFlash,
		common.EqSmoke, common.EqHE, common.EqWorld, common.EqFists,
		common.EqBreachCharge, common.EqTablet, common.EqAxe, common.EqHammer,
		common.EqWrench, common.EqSnowball, common.EqBumpMine, common.EqHealthShot:
		return false
	default:
		return true
	}
}

// roundEndReasonToString converte o RoundEndReason para o enum do Java.
func roundEndReasonToString(reason events.RoundEndReason) string {
	switch reason {
	case events.RoundEndReasonCTWin:
		return "CT_WIN_ELIMINATION"
	case events.RoundEndReasonTerroristsWin:
		return "TR_WIN_ELIMINATION"
	case events.RoundEndReasonTargetBombed:
		return "BOMB_EXPLODED"
	case events.RoundEndReasonBombDefused:
		return "BOMB_DEFUSED"
	case events.RoundEndReasonCTSurrender:
		return "CT_WIN_TIME"
	case events.RoundEndReasonTerroristsSurrender:
		return "TR_WIN_ELIMINATION"
	case events.RoundEndReasonTargetSaved:
		return "TARGET_SAVED"
	default:
		return "CT_WIN_TIME"
	}
}

// Helpers para criar ponteiros (necessários para omitempty em tipos primitivos)
func boolPtr(v bool) *bool          { return &v }
func float64Ptr(v float64) *float64 { return &v }
func intPtr(v int) *int             { return &v }
