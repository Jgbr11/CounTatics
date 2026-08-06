package parser

import (
	"fmt"
	"io"
	"log"
	"strconv"
	"sync"
	"time"

	demoinfocs "github.com/markus-wa/demoinfocs-golang/v4/pkg/demoinfocs"
	common "github.com/markus-wa/demoinfocs-golang/v4/pkg/demoinfocs/common"
	events "github.com/markus-wa/demoinfocs-golang/v4/pkg/demoinfocs/events"

	"github.com/countatic/demo-parser/internal/models"
)

// DemoParser encapsula a lógica de parsing de arquivos .dem do CS2.
// É thread-safe: cada chamada a Parse cria seu próprio parser interno.
type DemoParser struct{}

// NewDemoParser cria uma nova instância do parser.
func NewDemoParser() *DemoParser {
	return &DemoParser{}
}

// Parse lê um arquivo .dem a partir de um io.Reader e extrai todos os
// eventos relevantes, retornando a estrutura ParsedDemo pronta para
// serialização em JSON.
//
// Esta função é segura para chamadas concorrentes — cada invocação cria
// um parser independente do demoinfocs-golang.
func (dp *DemoParser) Parse(reader io.Reader) (*models.ParsedDemo, error) {
	p := demoinfocs.NewParser(reader)
	defer p.Close()

	result := &models.ParsedDemo{}

	// Mutex para proteger a escrita concorrente na lista de eventos do round atual.
	// O demoinfocs-golang dispara event handlers de forma síncrona, mas
	// usamos mutex por segurança caso a API interna mude.
	var mu sync.Mutex

	// Estado do round atual durante o parsing
	var currentRound *models.ParsedRound
	roundNumber := 0

	// ─── Header da Demo ──────────────────────────────────────────────
	// O header contém metadados como mapa, tick rate e duração.
	header, err := p.ParseHeader()
	if err != nil {
		return nil, fmt.Errorf("erro ao parsear header da demo: %w", err)
	}

	result.MapName = header.MapName
	result.ServerName = header.ServerName
	result.TickRate = int(p.TickRate())
	result.DurationSeconds = int(header.PlaybackTime.Seconds())
	result.PlayedAt = time.Now().UTC().Format(time.RFC3339)

	// ─── Evento: Início de Round ─────────────────────────────────────
	p.RegisterEventHandler(func(e events.RoundStart) {
		mu.Lock()
		defer mu.Unlock()

		roundNumber++
		currentRound = &models.ParsedRound{
			RoundNumber: roundNumber,
			StartTick:   p.CurrentFrame(),
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

		currentRound.EndTick = p.CurrentFrame()
		currentRound.WinnerSide = teamToString(common.Team(e.Winner))
		currentRound.EndReason = roundEndReasonToString(e.Reason)

		// Calcular duração do round em segundos
		tickRate := result.TickRate
		if tickRate > 0 {
			tickDiff := currentRound.EndTick - currentRound.StartTick
			currentRound.DurationSeconds = float64(tickDiff) / float64(tickRate)
		}

		// Capturar placar atual
		gs := p.GameState()
		currentRound.CTScoreAfter = gs.TeamCounterTerrorists().Score()
		currentRound.TRScoreAfter = gs.TeamTerrorists().Score()

		result.Rounds = append(result.Rounds, *currentRound)
		currentRound = nil
	})

	// ─── Evento: Kill ────────────────────────────────────────────────
	// Extrai quem matou, quem morreu, arma utilizada, se foi headshot,
	// posições e ângulos de visão.
	p.RegisterEventHandler(func(e events.Kill) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}

		event := models.ParsedEvent{
			EventType:  "KILL",
			Tick:       p.CurrentFrame(),
			IsHeadshot: boolPtr(e.IsHeadshot),
		}

		// Killer (ator)
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

		// Victim
		if e.Victim != nil {
			event.VictimSteamID = steamIDToString(e.Victim)
			event.VictimName = e.Victim.Name
			event.VictimSide = teamToString(e.Victim.Team)
			event.VictimPositionX = float64Ptr(e.Victim.Position().X)
			event.VictimPositionY = float64Ptr(e.Victim.Position().Y)
			event.VictimPositionZ = float64Ptr(e.Victim.Position().Z)
		}

		// Assister
		if e.Assister != nil {
			event.AssisterSteamID = steamIDToString(e.Assister)
			event.AssisterName = e.Assister.Name
		}

		// Arma
		if e.Weapon != nil {
			event.Weapon = e.Weapon.String()
		}

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Evento: FlashExplode ────────────────────────────────────────
	// Registra quando uma flashbang explode — identifica quem lançou e a posição.
	p.RegisterEventHandler(func(e events.FlashExplode) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}

		event := models.ParsedEvent{
			EventType: "FLASH_THROWN",
			Tick:      p.CurrentFrame(),
		}

		if e.Thrower != nil {
			event.ActorSteamID = steamIDToString(e.Thrower)
			event.ActorName = e.Thrower.Name
			event.ActorSide = teamToString(e.Thrower.Team)
		}

		// Posição de explosão da flash
		event.ActorPositionX = float64Ptr(e.Position.X)
		event.ActorPositionY = float64Ptr(e.Position.Y)
		event.ActorPositionZ = float64Ptr(e.Position.Z)

		currentRound.Events = append(currentRound.Events, event)
	})

	// ─── Evento: PlayerFlashed ───────────────────────────────────────
	// Registra quando um jogador é efetivamente cegado por uma flash.
	// Diferente de FlashExplode, este evento indica QUEM foi afetado.
	p.RegisterEventHandler(func(e events.PlayerFlashed) {
		mu.Lock()
		defer mu.Unlock()

		if currentRound == nil {
			return
		}

		event := models.ParsedEvent{
			EventType: "FLASH_BLINDED",
			Tick:      p.CurrentFrame(),
		}

		// Quem lançou a flash (attacker)
		if e.Attacker != nil {
			event.ActorSteamID = steamIDToString(e.Attacker)
			event.ActorName = e.Attacker.Name
			event.ActorSide = teamToString(e.Attacker.Team)
		}

		// Quem foi cegado (victim)
		if e.Player != nil {
			event.VictimSteamID = steamIDToString(e.Player)
			event.VictimName = e.Player.Name
			event.VictimSide = teamToString(e.Player.Team)

			// Determinar se a flash cegou um inimigo ou aliado
			if e.Attacker != nil {
				isEnemy := e.Attacker.Team != e.Player.Team
				event.IsEnemyFlash = boolPtr(isEnemy)
			}
		}

		// Duração da cegueira em segundos
		flashDuration := e.FlashDuration().Seconds()
		event.FlashDurationSecs = float64Ptr(flashDuration)

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
			Tick:      p.CurrentFrame(),
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
			Tick:      p.CurrentFrame(),
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
	err = p.ParseToEnd()
	if err != nil {
		return nil, fmt.Errorf("erro durante o parsing da demo: %w", err)
	}

	// ─── Metadados finais ─────────────────────────────────────────────
	result.TotalRounds = len(result.Rounds)
	if result.TotalRounds > 0 {
		lastRound := result.Rounds[result.TotalRounds-1]
		result.ScoreCT = lastRound.CTScoreAfter
		result.ScoreTR = lastRound.TRScoreAfter
	}

	log.Printf("Parsing concluído: mapa=%s, rounds=%d, placar=%d-%d",
		result.MapName, result.TotalRounds, result.ScoreCT, result.ScoreTR)

	return result, nil
}

// ═══════════════════════════════════════════════════════════════════
//  UTILITÁRIOS DE CONVERSÃO
// ═══════════════════════════════════════════════════════════════════

// steamIDToString converte o SteamID de um jogador para o formato SteamID64 (string de 17 dígitos).
func steamIDToString(player *common.Player) string {
	return strconv.FormatUint(player.SteamID64, 10)
}

// teamToString converte a enum Team do demoinfocs para as strings que o Java espera.
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

// roundEndReasonToString converte o RoundEndReason para as strings do enum RoundEndReason do Java.
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
func boolPtr(v bool) *bool       { return &v }
func float64Ptr(v float64) *float64 { return &v }
