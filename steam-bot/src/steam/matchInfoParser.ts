import SteamID from "steamid";
import type { GCMatchInfo, GCPlayerStats } from "./types";

/**
 * Conversão da resposta crua do Game Coordinator do CS2 para o contrato
 * consumido pelo core-backend.
 *
 * <p>Vive fora de {@code SteamClientManager} de propósito: é lógica pura, sem
 * sessão da Steam, sem rede e sem estado. Isso a torna testável de forma
 * direta — o que importa, porque foi exatamente aqui que um bug de alinhamento
 * de índices atribuiu as estatísticas ao jogador errado em produção.</p>
 */

/** Resolve nomes de exibição; devolve mapa vazio se não for possível. */
export type PersonaResolver = (steamIds: string[]) => Promise<Map<string, string>>;

export interface ParseWarning {
  code: "RESERVATION_FALLBACK" | "NO_RESERVATION" | "REQUESTER_NOT_FOUND" | "DEMO_URL_INVALID";
  message: string;
}

export interface ParseResult {
  matchInfo: GCMatchInfo;
  warnings: ParseWarning[];
}

/**
 * Localiza a entrada de roundstats que fornece as estatísticas finais e a
 * lista de jogadores correspondente.
 *
 * ⚠️ **Ponto crítico.** Os arrays `kills`/`deaths`/... são indexados por
 * `reservation.account_ids` **da mesma entrada**. Entradas diferentes podem
 * trazer ordens de jogador diferentes.
 *
 * Uma versão anterior lia `account_ids` da *primeira* entrada com reserva e as
 * estatísticas da *última*. Quando as ordens divergiam, as estatísticas iam
 * para o jogador errado — verificado contra a demo: o relatório informou 18/17
 * a um jogador que na verdade fez 13/15, números que pertenciam a outro.
 * Apenas 4 de 10 jogadores ficavam corretos, por coincidência.
 */
export function resolveStatsSource(match: any): {
  stats: any | null;
  accountIds: number[];
  warnings: ParseWarning[];
} {
  const warnings: ParseWarning[] = [];

  const roundStatsAll: any[] = Array.isArray(match?.roundstatsall)
    ? match.roundstatsall
    : match?.roundstats_legacy
      ? [match.roundstats_legacy]
      : [];

  const stats = roundStatsAll.length > 0 ? roundStatsAll[roundStatsAll.length - 1] : null;

  // Caminho correto: a reserva da MESMA entrada das estatísticas.
  const aligned: number[] = stats?.reservation?.account_ids ?? [];
  if (aligned.length) {
    return { stats, accountIds: aligned, warnings };
  }

  // Fallback: retrocede para a entrada mais próxima que tenha reserva.
  // O alinhamento deixa de ser garantido, então avisa.
  for (let i = roundStatsAll.length - 2; i >= 0; i--) {
    const ids = roundStatsAll[i]?.reservation?.account_ids;
    if (ids?.length) {
      warnings.push({
        code: "RESERVATION_FALLBACK",
        message:
          `A última entrada de roundstats não traz reservation; usando a da entrada ${i}. ` +
          `Se a ordem dos jogadores mudou durante a partida, as estatísticas podem sair trocadas.`,
      });
      return { stats, accountIds: ids, warnings };
    }
  }

  const legacy: number[] = match?.roundstats_legacy?.reservation?.account_ids ?? [];
  if (legacy.length) {
    warnings.push({
      code: "RESERVATION_FALLBACK",
      message: "Usando reservation de roundstats_legacy; alinhamento não garantido.",
    });
    return { stats, accountIds: legacy, warnings };
  }

  warnings.push({
    code: "NO_RESERVATION",
    message: "Nenhuma reservation encontrada — impossível identificar os jogadores.",
  });
  return { stats, accountIds: [], warnings };
}

/**
 * Extrai a URL do replay.
 *
 * O campo `map` das round stats **já é a URL completa** do CDN da Valve
 * (ex: `http://replay202.valve.net/730/....dem.bz2`), não um identificador a
 * ser interpolado num template. Só é aceito se de fato parecer uma URL.
 */
export function extractDemoUrl(match: any, stats: any): { url: string | null; warning?: ParseWarning } {
  const raw: unknown = stats?.map ?? match?.roundstats_legacy?.map;

  if (typeof raw === "string" && /^https?:\/\//i.test(raw)) {
    return { url: raw };
  }

  if (raw) {
    return {
      url: null,
      warning: {
        code: "DEMO_URL_INVALID",
        message: `Campo 'map' não parece uma URL de demo: ${String(raw)}`,
      },
    };
  }

  return { url: null };
}

/**
 * Extrai o nome do mapa.
 *
 * Em partidas de CS2 o GC devolve `game_mapgroup`, `game_map` e `game_type`
 * todos `null` (verificado empiricamente) — o mapa só sai do parsing da demo.
 * Devolve `null` em vez de "unknown" para o relatório poder omitir a linha.
 */
export function extractMapName(match: any): string | null {
  const raw: string | null =
    match?.watchablematchinfo?.game_mapgroup || match?.watchablematchinfo?.game_map || null;
  return raw ? raw.replace(/^mg_/, "") : null;
}

/** Converte um AccountID de 32 bits em SteamID64. */
export function accountIdToSteamId64(accountId: number | null | undefined): string {
  if (!accountId) return "0";
  return new SteamID(`[U:1:${accountId}]`).getSteamID64();
}

export interface RankInfo {
  rank: number | null;
  rankTypeId: number | null;
}

/**
 * Extrai o rating de cada jogador a partir de `reservation.rankings`.
 *
 * Casa por `account_id`, que vem **dentro** de cada `PlayerRankingInfo`, e não
 * por posição no array. Isso torna a extração imune ao problema de alinhamento
 * que afetou as estatísticas: mesmo que a ordem de `rankings` difira da de
 * `account_ids`, cada rating vai para o dono certo.
 */
export function extractRankings(stats: any): Map<number, RankInfo> {
  const out = new Map<number, RankInfo>();
  const rankings: any[] = stats?.reservation?.rankings ?? [];

  for (const r of rankings) {
    const accountId = r?.account_id;
    if (!accountId) continue;

    out.set(accountId, {
      rank: typeof r.rank_id === "number" ? r.rank_id : null,
      rankTypeId: typeof r.rank_type_id === "number" ? r.rank_type_id : null,
    });
  }

  return out;
}

/**
 * Média dos ratings informados.
 *
 * Ignora zeros: em Premier, rating 0 significa "ainda não calibrado", não
 * "jogador ruim" — incluí-lo puxaria a média da partida para baixo sem motivo.
 */
export function averageRank(players: { rank: number | null }[]): number | null {
  const valores = players.map((p) => p.rank).filter((r): r is number => typeof r === "number" && r > 0);
  if (!valores.length) return null;
  return Math.round(valores.reduce((a, b) => a + b, 0) / valores.length);
}

/**
 * Monta o {@link GCMatchInfo} a partir da resposta crua do GC.
 *
 * @param requesterSteamId quando informado, orienta o placar pelo time desse
 *   jogador. Sem isso, metade dos usuários receberia o placar invertido.
 */
export async function parseGcMatch(
  match: any,
  resolvePersonas: PersonaResolver,
  requesterSteamId?: string
): Promise<ParseResult> {
  const { stats, accountIds, warnings } = resolveStatsSource(match);

  const kills: number[] = stats?.kills ?? [];
  const deaths: number[] = stats?.deaths ?? [];
  const assists: number[] = stats?.assists ?? [];
  const scores: number[] = stats?.scores ?? [];
  const mvps: number[] = stats?.mvps ?? [];
  const enemyHeadshots: number[] = stats?.enemy_headshots ?? [];

  const steamIds = accountIds.map(accountIdToSteamId64);
  const personas = steamIds.length ? await resolvePersonas(steamIds) : new Map<string, string>();
  const rankings = extractRankings(stats);

  const players: GCPlayerStats[] = steamIds.map((steamId64, i) => {
    // Casado por account_id, não por posição — ver extractRankings.
    const rk = rankings.get(accountIds[i]) ?? { rank: null, rankTypeId: null };

    return {
      steamId64,
      playerName: personas.get(steamId64) ?? `Player${i + 1}`,
      kills: kills[i] ?? 0,
      deaths: deaths[i] ?? 0,
      assists: assists[i] ?? 0,
      headshots: enemyHeadshots[i] ?? 0,
      mvps: mvps[i] ?? 0,
      score: scores[i] ?? 0,
      // Os lados trocam no intervalo, então rotular CT/TR para a partida inteira
      // seria dado inventado. Índices 0-4 = time A, 5-9 = time B.
      team: i < 5 ? "A" : "B",
      teamIndex: i < 5 ? 0 : 1,
      rank: rk.rank,
      rankTypeId: rk.rankTypeId,
    };
  });

  const teamScores: number[] = stats?.team_scores ?? [];

  let requesterTeamIndex = 0;
  if (requesterSteamId) {
    const me = players.find((p) => p.steamId64 === requesterSteamId);
    if (me) {
      requesterTeamIndex = me.teamIndex;
    } else if (players.length) {
      warnings.push({
        code: "REQUESTER_NOT_FOUND",
        message: `SteamID ${requesterSteamId} não está entre os jogadores da partida.`,
      });
    }
  }

  const demo = extractDemoUrl(match, stats);
  if (demo.warning) warnings.push(demo.warning);

  const matchInfo: GCMatchInfo = {
    matchId: match?.matchid?.toString() ?? "0",
    // `matchtime` é o TIMESTAMP DE INÍCIO (unix), não a duração.
    // A duração real vem de `match_duration` nas round stats.
    matchTimeUnix: Number(match?.matchtime) || 0,
    matchDuration: Number(stats?.match_duration) || 0,
    mapName: extractMapName(match),
    matchResult: "completed",
    roundsWon: teamScores[requesterTeamIndex] ?? 0,
    roundsLost: teamScores[1 - requesterTeamIndex] ?? 0,
    teamScores,
    demoUrl: demo.url,
    players,
    averageRank: averageRank(players),
  };

  return { matchInfo, warnings };
}
