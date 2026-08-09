/**
 * Contratos compartilhados entre o cliente Steam e o parser de match info.
 *
 * Ficam num módulo próprio para que o parser possa ser importado (e testado)
 * sem arrastar junto o `SteamClientManager`, que instancia sessão da Steam.
 */

/** Informações de uma partida retornadas pelo Game Coordinator do CS2. */
export interface GCMatchInfo {
  matchId: string;
  /** Timestamp unix de INÍCIO da partida (campo `matchtime` do GC). */
  matchTimeUnix: number;
  /** Duração real em segundos (`match_duration` das round stats). */
  matchDuration: number;
  /** `null` em CS2: o GC não informa o mapa. Só o parsing da demo revela. */
  mapName: string | null;
  matchResult: string;
  /** Placar orientado pelo time de quem solicitou. */
  roundsWon: number;
  roundsLost: number;
  /** Placar bruto por índice de time, sem orientação. */
  teamScores: number[];
  demoUrl: string | null;
  players: GCPlayerStats[];

  /**
   * Média dos ratings vindos de `reservation.rankings`.
   *
   * Em CS2 esse campo costuma chegar VAZIO — o GC não informa ranking na
   * resposta da partida. Fica aqui porque o protobuf prevê, mas na prática o
   * nível da partida vem de {@link requesterRank}.
   */
  averageRank: number | null;

  /**
   * CS Rating (Premier) de quem solicitou, consultado no perfil do GC.
   *
   * É o que define a faixa de comparação. Como o matchmaking pareia jogadores
   * de nível parecido, uma única consulta caracteriza o nível da partida
   * inteira — evitando 10 consultas ao GC, que levariam ~30 s pelo rate limit.
   */
  requesterRank: number | null;
}

export interface GCPlayerStats {
  steamId64: string;
  playerName: string;
  kills: number;
  deaths: number;
  assists: number;
  headshots: number;
  mvps: number;
  score: number;
  /** "A" ou "B" — CT/TR não é recuperável para a partida toda (lados trocam). */
  team: string;
  teamIndex: number;

  /**
   * Rating do jogador na fila em que a partida foi jogada.
   *
   * Em Premier (`rankTypeId` 11) é o CS Rating propriamente dito — o número de
   * 4-5 dígitos que aparece no jogo. Em competitivo clássico é o índice da
   * patente (1-18). `null` quando o GC não informa.
   *
   * É a base para comparar o desempenho contra jogadores de nível parecido,
   * em vez de contra uma média global sem sentido.
   */
  rank: number | null;

  /** Tipo de fila: 11 = Premier, 6 = competitivo por mapa, 7 = Wingman. */
  rankTypeId: number | null;
}

/**
 * Resultado discriminado de uma consulta ao Game Coordinator.
 *
 * Existe para o backend separar falha transitória (retentar) de falha terminal
 * (desistir). Antes tudo colapsava em `null`.
 */
export type MatchInfoResult =
  | { status: "OK"; matchInfo: GCMatchInfo }
  | { status: "GC_UNAVAILABLE" }
  | { status: "TIMEOUT" }
  | { status: "NOT_FOUND" }
  | { status: "PARSE_ERROR"; error: string };

/** Resultado do envio de uma mensagem via Steam Chat. */
export interface SendMessageResult {
  success: boolean;
  steamId: string;
  error?: string;
}
