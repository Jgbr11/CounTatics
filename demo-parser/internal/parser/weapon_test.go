package parser

import (
	"testing"

	common "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs/common"
)

// TestWeaponToID_UtilitariasCasamComOJava é o teste mais importante deste pacote.
//
// O Java (UtilityStatStrategy.DAMAGE_UTILITIES) casa o dano de utilitária
// contra IDS INTERNOS. Se o parser emitir o nome de exibição do demoinfocs
// ("HE Grenade", "Molotov"), nenhum match acontece e TODA métrica de dano de
// utilitária fica zerada — silenciosamente, sem erro em lugar nenhum.
//
// Foi exatamente esse o estado do sistema antes: Equipment.String() devolvia
// nomes de exibição enquanto o Java procurava "hegrenade"/"inferno"/"molotov".
func TestWeaponToID_UtilitariasCasamComOJava(t *testing.T) {
	// Conjunto exato que o Java procura em DAMAGE_UTILITIES.
	javaEsperaUmDestes := map[common.EquipmentType][]string{
		common.EqHE:         {"hegrenade", "he_grenade"},
		common.EqMolotov:    {"molotov"},
		common.EqIncendiary: {"inferno", "incgrenade", "inc_grenade"},
	}

	for eqType, aceitos := range javaEsperaUmDestes {
		eq := &common.Equipment{Type: eqType}
		got := weaponToID(eq)

		ok := false
		for _, a := range aceitos {
			if got == a {
				ok = true
				break
			}
		}

		if !ok {
			t.Errorf("weaponToID(%v) = %q; o Java não reconhece esse valor como "+
				"utilitária de dano (esperado um de %v). "+
				"Com isso, utilityDamage fica zerado sem nenhum erro visível.",
				eqType, got, aceitos)
		}
	}
}

// TestWeaponToID_NaoUsaNomeDeExibicao garante que não voltamos a emitir
// Equipment.String(), que devolve "AK-47" em vez de "ak47".
func TestWeaponToID_NaoUsaNomeDeExibicao(t *testing.T) {
	casos := []struct {
		tipo     common.EquipmentType
		proibido string
	}{
		{common.EqAK47, "AK-47"},
		{common.EqHE, "HE Grenade"},
		{common.EqMolotov, "Molotov"},
		{common.EqSmoke, "Smoke Grenade"},
		{common.EqAWP, "AWP"},
	}

	for _, c := range casos {
		got := weaponToID(&common.Equipment{Type: c.tipo})
		if got == c.proibido {
			t.Errorf("weaponToID(%v) devolveu o nome de exibição %q; "+
				"esperado o id interno em minúsculas", c.tipo, got)
		}
		if got == "" {
			t.Errorf("weaponToID(%v) devolveu vazio; arma conhecida sem mapeamento", c.tipo)
		}
	}
}

func TestWeaponToID_ArmasComuns(t *testing.T) {
	casos := map[common.EquipmentType]string{
		common.EqAK47:    "ak47",
		common.EqAWP:     "awp",
		common.EqM4A4:    "m4a1",
		common.EqM4A1:    "m4a1_silencer",
		common.EqGlock:   "glock",
		common.EqDeagle:  "deagle",
		common.EqUSP:     "usp_silencer",
		common.EqSmoke:   "smokegrenade",
		common.EqFlash:   "flashbang",
		common.EqKnife:   "knife",
		common.EqBomb:    "c4",
		common.EqUnknown: "",
	}

	for tipo, esperado := range casos {
		if got := weaponToID(&common.Equipment{Type: tipo}); got != esperado {
			t.Errorf("weaponToID(%v) = %q; esperado %q", tipo, got, esperado)
		}
	}
}

func TestWeaponToID_NilNaoExplode(t *testing.T) {
	if got := weaponToID(nil); got != "" {
		t.Errorf("weaponToID(nil) = %q; esperado vazio", got)
	}
}

// TestGrenadeEventType_CasamComOEnumDoJava: os valores precisam existir em
// EventType.java, senão a desserialização da partida inteira falha.
func TestGrenadeEventType_CasamComOEnumDoJava(t *testing.T) {
	casos := map[common.EquipmentType]string{
		common.EqSmoke:      "SMOKE_THROWN",
		common.EqHE:         "HE_THROWN",
		common.EqMolotov:    "MOLOTOV_THROWN",
		common.EqIncendiary: "MOLOTOV_THROWN",

		// Flashbang é contabilizada via FlashExplode, não no arremesso:
		// mudar isso alteraria o denominador de flashEfficiency e deslocaria
		// silenciosamente todas as métricas históricas.
		common.EqFlash: "",
		// Decoy não alimenta métrica alguma.
		common.EqDecoy: "",
		// Não-granadas nunca geram evento de arremesso.
		common.EqAK47: "",
	}

	for tipo, esperado := range casos {
		if got := grenadeEventType(&common.Equipment{Type: tipo}); got != esperado {
			t.Errorf("grenadeEventType(%v) = %q; esperado %q", tipo, got, esperado)
		}
	}

	if got := grenadeEventType(nil); got != "" {
		t.Errorf("grenadeEventType(nil) = %q; esperado vazio", got)
	}
}

// TestIsAimWeapon: WEAPON_FIRE alimenta crosshair placement, então granada,
// faca e zeus precisam ficar de fora — não dizem nada sobre mira.
func TestIsAimWeapon(t *testing.T) {
	devemContar := []common.EquipmentType{
		common.EqAK47, common.EqAWP, common.EqM4A4, common.EqDeagle,
		common.EqGlock, common.EqP90, common.EqSSG08,
	}
	naoDevemContar := []common.EquipmentType{
		common.EqKnife, common.EqBomb, common.EqZeus, common.EqFlash,
		common.EqSmoke, common.EqHE, common.EqMolotov, common.EqIncendiary,
		common.EqDecoy, common.EqUnknown, common.EqWorld,
	}

	for _, tipo := range devemContar {
		if !isAimWeapon(tipo) {
			t.Errorf("isAimWeapon(%v) = false; arma de mira deveria contar", tipo)
		}
	}
	for _, tipo := range naoDevemContar {
		if isAimWeapon(tipo) {
			t.Errorf("isAimWeapon(%v) = true; não deveria entrar em métrica de mira", tipo)
		}
	}
}

// TestTeamToString: as strings precisam bater com o enum Team do Java.
func TestTeamToString(t *testing.T) {
	casos := map[common.Team]string{
		common.TeamCounterTerrorists: "CT",
		common.TeamTerrorists:        "TR",
		common.TeamSpectators:        "",
		common.TeamUnassigned:        "",
	}

	for time, esperado := range casos {
		if got := teamToString(time); got != esperado {
			t.Errorf("teamToString(%v) = %q; esperado %q", time, got, esperado)
		}
	}
}
