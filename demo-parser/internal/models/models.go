package models

// ParsedDemo é a estrutura raiz retornada pelo parser.
// Mapeia 1:1 com o ParsedDemoDTO do Core Backend (Java).
type ParsedDemo struct {
	MapName         string         `json:"mapName"`
	ServerName      string         `json:"serverName,omitempty"`
	DurationSeconds int            `json:"durationSeconds"`
	ScoreCT         int            `json:"scoreCT"`
	ScoreTR         int            `json:"scoreTR"`
	TotalRounds     int            `json:"totalRounds"`
	TickRate        int            `json:"tickRate"`
	PlayedAt        string         `json:"playedAt"`
	Rounds          []ParsedRound  `json:"rounds"`
}

// ParsedRound representa um round individual da partida.
type ParsedRound struct {
	RoundNumber     int            `json:"roundNumber"`
	WinnerSide      string         `json:"winnerSide"`
	EndReason       string         `json:"endReason"`
	StartTick       int            `json:"startTick"`
	EndTick         int            `json:"endTick"`
	DurationSeconds float64        `json:"durationSeconds"`
	BombPlanted     bool           `json:"bombPlanted"`
	BombDefused     bool           `json:"bombDefused"`
	CTScoreAfter    int            `json:"ctScoreAfter"`
	TRScoreAfter    int            `json:"trScoreAfter"`
	Events          []ParsedEvent  `json:"events"`
}

// ParsedEvent representa um evento individual extraído do .dem.
// Campos irrelevantes para o tipo de evento serão omitidos no JSON (omitempty).
type ParsedEvent struct {
	EventType string `json:"eventType"`
	Tick      int    `json:"tick"`

	// Participantes (identificados por SteamID64)
	ActorSteamID  string `json:"actorSteamId,omitempty"`
	ActorName     string `json:"actorName,omitempty"`
	ActorSide     string `json:"actorSide,omitempty"`
	VictimSteamID string `json:"victimSteamId,omitempty"`
	VictimName    string `json:"victimName,omitempty"`
	VictimSide    string `json:"victimSide,omitempty"`
	AssisterSteamID string `json:"assisterSteamId,omitempty"`
	AssisterName    string `json:"assisterName,omitempty"`

	// Detalhes do evento
	Weapon             string  `json:"weapon,omitempty"`
	IsHeadshot         *bool   `json:"isHeadshot,omitempty"`
	DamageAmount       *int    `json:"damageAmount,omitempty"`
	DamageArmor        *int    `json:"damageArmor,omitempty"`
	FlashDurationSecs  *float64 `json:"flashDurationSeconds,omitempty"`
	IsEnemyFlash       *bool   `json:"isEnemyFlash,omitempty"`

	// Dados espaciais
	ActorPositionX  *float64 `json:"actorPositionX,omitempty"`
	ActorPositionY  *float64 `json:"actorPositionY,omitempty"`
	ActorPositionZ  *float64 `json:"actorPositionZ,omitempty"`
	VictimPositionX *float64 `json:"victimPositionX,omitempty"`
	VictimPositionY *float64 `json:"victimPositionY,omitempty"`
	VictimPositionZ *float64 `json:"victimPositionZ,omitempty"`
	ViewAngleX      *float64 `json:"viewAngleX,omitempty"`
	ViewAngleY      *float64 `json:"viewAngleY,omitempty"`
}
