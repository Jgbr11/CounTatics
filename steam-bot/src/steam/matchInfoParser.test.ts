import { test, describe } from "node:test";
import assert from "node:assert/strict";

import {
  parseGcMatch,
  resolveStatsSource,
  extractDemoUrl,
  extractMapName,
  accountIdToSteamId64,
} from "./matchInfoParser";

/** Sem personas: o parser deve cair no nome posicional. */
const noPersonas = async () => new Map<string, string>();

/**
 * Monta uma entrada de roundstats.
 * `accountIds` é a ordem dos jogadores DESTA entrada; os arrays de stats
 * seguem essa mesma ordem.
 */
function roundStats(opts: {
  accountIds?: number[];
  kills?: number[];
  deaths?: number[];
  assists?: number[];
  headshots?: number[];
  teamScores?: number[];
  map?: string;
  duration?: number;
}) {
  const entry: any = {
    kills: opts.kills,
    deaths: opts.deaths,
    assists: opts.assists,
    enemy_headshots: opts.headshots,
    team_scores: opts.teamScores,
    map: opts.map,
    match_duration: opts.duration,
  };
  if (opts.accountIds) {
    entry.reservation = { account_ids: opts.accountIds };
  }
  return entry;
}

describe("alinhamento de índices das estatísticas do GC", () => {
  /**
   * REGRESSÃO — bug encontrado em produção (2026-08-09).
   *
   * Os arrays kills/deaths são indexados por `reservation.account_ids` DA MESMA
   * entrada de roundstats. O código lia account_ids da PRIMEIRA entrada com
   * reserva e as stats da ÚLTIMA; quando as ordens divergiam, as estatísticas
   * iam para o jogador errado.
   *
   * Caso real: o relatório informou 18/17 a um jogador que fizera 13/15 —
   * números que pertenciam a outro participante. Só 4 de 10 ficavam corretos.
   */
  test("usa a reservation da MESMA entrada que fornece as estatísticas", async () => {
    const A = 1001; // vira SteamID64 …
    const B = 1002;

    const match = {
      matchid: "42",
      // Primeira entrada: ordem [A, B]
      roundstatsall: [
        roundStats({ accountIds: [A, B], kills: [1, 0], deaths: [0, 1] }),
        // Última entrada: ordem INVERTIDA [B, A] com as stats finais.
        roundStats({
          accountIds: [B, A],
          kills: [18, 13],
          deaths: [17, 15],
          teamScores: [13, 8],
        }),
      ],
    };

    const { matchInfo } = await parseGcMatch(match, noPersonas);

    const idA = accountIdToSteamId64(A);
    const idB = accountIdToSteamId64(B);

    const a = matchInfo.players.find((p) => p.steamId64 === idA)!;
    const b = matchInfo.players.find((p) => p.steamId64 === idB)!;

    // Com o alinhamento correto, B (primeiro na última entrada) tem 18/17.
    assert.equal(b.kills, 18, "B deveria ter as stats do índice 0 da última entrada");
    assert.equal(b.deaths, 17);
    assert.equal(a.kills, 13, "A deveria ter as stats do índice 1 da última entrada");
    assert.equal(a.deaths, 15);

    // O bug antigo produziria exatamente o contrário.
    assert.notEqual(a.kills, 18, "regressão: stats atribuídas ao jogador errado");
  });

  test("avisa quando precisa retroceder para achar a reservation", async () => {
    const match = {
      matchid: "43",
      roundstatsall: [
        roundStats({ accountIds: [2001, 2002] }),
        // Última entrada sem reservation: alinhamento deixa de ser garantido.
        roundStats({ kills: [5, 3], deaths: [2, 4] }),
      ],
    };

    const { accountIds, warnings } = resolveStatsSource(match);

    assert.deepEqual(accountIds, [2001, 2002]);
    assert.equal(warnings.length, 1);
    assert.equal(warnings[0].code, "RESERVATION_FALLBACK");
  });

  test("sinaliza quando não há reservation alguma", async () => {
    const { accountIds, warnings } = resolveStatsSource({
      roundstatsall: [roundStats({ kills: [1] })],
    });

    assert.deepEqual(accountIds, []);
    assert.equal(warnings[0].code, "NO_RESERVATION");
  });

  test("aceita roundstats_legacy quando não há roundstatsall", async () => {
    const match = {
      matchid: "44",
      roundstats_legacy: roundStats({
        accountIds: [3001],
        kills: [7],
        deaths: [2],
        teamScores: [13, 4],
      }),
    };

    const { matchInfo } = await parseGcMatch(match, noPersonas);
    assert.equal(matchInfo.players.length, 1);
    assert.equal(matchInfo.players[0].kills, 7);
  });
});

describe("orientação do placar", () => {
  test("orienta o placar pelo time de quem solicitou", async () => {
    // 10 jogadores: índices 0-4 = time A, 5-9 = time B.
    const ids = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    const match = {
      matchid: "50",
      roundstatsall: [
        roundStats({
          accountIds: ids,
          kills: new Array(10).fill(0),
          deaths: new Array(10).fill(0),
          teamScores: [13, 8],
        }),
      ],
    };

    // Solicitante no time A (índice 0) → venceu 13-8.
    const timeA = await parseGcMatch(match, noPersonas, accountIdToSteamId64(1));
    assert.equal(timeA.matchInfo.roundsWon, 13);
    assert.equal(timeA.matchInfo.roundsLost, 8);

    // Solicitante no time B (índice 6) → perdeu 8-13.
    // Sem essa orientação, metade dos usuários receberia o placar invertido.
    const timeB = await parseGcMatch(match, noPersonas, accountIdToSteamId64(7));
    assert.equal(timeB.matchInfo.roundsWon, 8);
    assert.equal(timeB.matchInfo.roundsLost, 13);
  });

  test("avisa quando o solicitante não está na partida", async () => {
    const match = {
      matchid: "51",
      roundstatsall: [
        roundStats({ accountIds: [1, 2], kills: [1, 2], deaths: [2, 1], teamScores: [13, 5] }),
      ],
    };

    const { warnings } = await parseGcMatch(match, noPersonas, "76561199999999999");
    assert.ok(warnings.some((w) => w.code === "REQUESTER_NOT_FOUND"));
  });
});

describe("URL da demo", () => {
  test("aceita a URL completa vinda do campo map", () => {
    // O campo `map` das round stats JÁ É a URL do CDN da Valve.
    const url = "http://replay202.valve.net/730/003835751950364704778_0322641992.dem.bz2";
    const { url: got } = extractDemoUrl({}, { map: url });
    assert.equal(got, url);
  });

  test("rejeita valor que não é URL, em vez de montar template", () => {
    // REGRESSÃO: a versão antiga interpolava esse valor num template
    // (`http://replay${map}.valve.net/...`), gerando URL corrompida.
    const { url, warning } = extractDemoUrl({}, { map: "202" });
    assert.equal(url, null);
    assert.equal(warning?.code, "DEMO_URL_INVALID");
  });

  test("devolve null quando não há campo map", () => {
    const { url, warning } = extractDemoUrl({}, {});
    assert.equal(url, null);
    assert.equal(warning, undefined);
  });
});

describe("nome do mapa", () => {
  test("devolve null quando o GC não informa (comportamento do CS2)", () => {
    // Verificado empiricamente: em CS2 o GC manda game_mapgroup, game_map e
    // game_type todos null. Devolver null (e não "unknown") permite ao
    // relatório omitir a linha em vez de exibir um valor falso.
    const mapa = extractMapName({
      watchablematchinfo: { game_mapgroup: null, game_map: null, game_type: null },
    });
    assert.equal(mapa, null);
  });

  test("remove o prefixo mg_ quando o mapgroup vem preenchido", () => {
    assert.equal(
      extractMapName({ watchablematchinfo: { game_mapgroup: "mg_de_mirage" } }),
      "de_mirage"
    );
  });
});

describe("campos temporais", () => {
  test("matchtime é o INÍCIO da partida, não a duração", async () => {
    // REGRESSÃO: `matchtime` já foi mapeado como duração. São coisas distintas —
    // a duração real vive em `match_duration` nas round stats.
    const match = {
      matchid: "60",
      matchtime: 1786151381,
      roundstatsall: [
        roundStats({ accountIds: [1], kills: [1], deaths: [0], duration: 2292 }),
      ],
    };

    const { matchInfo } = await parseGcMatch(match, noPersonas);
    assert.equal(matchInfo.matchTimeUnix, 1786151381);
    assert.equal(matchInfo.matchDuration, 2292);
  });
});

describe("nomes dos jogadores", () => {
  test("usa a persona resolvida quando disponível", async () => {
    const id = accountIdToSteamId64(1);
    const match = {
      matchid: "70",
      roundstatsall: [roundStats({ accountIds: [1], kills: [5], deaths: [1] })],
    };

    const { matchInfo } = await parseGcMatch(
      match,
      async () => new Map([[id, "JGBR11"]])
    );
    assert.equal(matchInfo.players[0].playerName, "JGBR11");
  });

  test("cai no nome posicional se a persona não resolver", async () => {
    const match = {
      matchid: "71",
      roundstatsall: [roundStats({ accountIds: [1, 2], kills: [5, 3], deaths: [1, 2] })],
    };

    const { matchInfo } = await parseGcMatch(match, noPersonas);
    assert.equal(matchInfo.players[0].playerName, "Player1");
    assert.equal(matchInfo.players[1].playerName, "Player2");
  });
});
