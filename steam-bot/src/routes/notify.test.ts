import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import express from "express";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";

import { createRouter } from "./notify";
import { createMatchRoutes } from "./match";
import { SteamClientStatus } from "../steam/client";
import type { SteamClientManager } from "../steam/client";
import type { MatchInfoResult, SendMessageResult } from "../steam/types";

/**
 * Dublê do SteamClientManager.
 *
 * Permite controlar login e estado do GC de forma INDEPENDENTE — que é
 * justamente o ponto: por muito tempo os dois estavam acoplados, e o envio de
 * mensagens quebrava no instante em que o Game Coordinator conectava.
 */
class FakeSteamClient {
  loggedIn = true;
  gcReady = false;
  matchResult: MatchInfoResult = { status: "GC_UNAVAILABLE" };
  enviadas: Array<{ steamId: string; message: string }> = [];
  falharEnvio = false;

  isLoggedIn() {
    return this.loggedIn;
  }
  isGcReady() {
    return this.gcReady;
  }
  getStatus() {
    return this.loggedIn ? SteamClientStatus.LOGGED_IN : SteamClientStatus.DISCONNECTED;
  }
  isWaitingSteamGuard() {
    return false;
  }
  getPendingGuardDomain() {
    return null;
  }
  submitSteamGuardCode() {
    return true;
  }
  async sendMessage(steamId: string, message: string): Promise<SendMessageResult> {
    if (this.falharEnvio) {
      return { success: false, steamId, error: "RateLimitExceeded" };
    }
    this.enviadas.push({ steamId, message });
    return { success: true, steamId };
  }
  async requestMatchInfo(): Promise<MatchInfoResult> {
    return this.matchResult;
  }
}

const STEAM_ID = "76561199110265389";

let fake: FakeSteamClient;
let server: Server;
let base: string;

before(async () => {
  fake = new FakeSteamClient();
  const app = express();
  app.use(express.json());
  const cliente = fake as unknown as SteamClientManager;
  app.use("/", createRouter(cliente));
  app.use("/", createMatchRoutes(cliente));

  await new Promise<void>((resolve) => {
    server = app.listen(0, () => resolve());
  });
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(async () => {
  // O fetch global (undici) mantém sockets keep-alive abertos; sem derrubá-los
  // o processo de teste fica ~1 min pendurado esperando o servidor fechar.
  server.closeAllConnections?.();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

const post = (path: string, body: unknown) =>
  fetch(base + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

describe("POST /notify", () => {
  /**
   * REGRESSÃO — o bug mais perigoso já presente neste serviço.
   *
   * `isReady()` era `status === LOGGED_IN`, mas conectar ao Game Coordinator
   * mudava o status para `GC_CONNECTED`. Consequência: no exato momento em que
   * o GC finalmente conectasse, TODO envio de mensagem passaria a devolver 503 —
   * ou seja, consertar o GC quebraria a entrega de relatórios.
   *
   * Se este teste falhar, o acoplamento voltou.
   */
  test("responde 200 mesmo com o GC CONECTADO", async () => {
    fake.loggedIn = true;
    fake.gcReady = true; // <- a condição que quebrava tudo

    const res = await post("/notify", { steamId: STEAM_ID, message: "oi" });

    assert.equal(res.status, 200, "enviar chat não pode depender do estado do GC");
    const body = (await res.json()) as any;
    assert.equal(body.success, true);
  });

  test("responde 200 com o GC desconectado", async () => {
    fake.loggedIn = true;
    fake.gcReady = false;

    const res = await post("/notify", { steamId: STEAM_ID, message: "oi" });
    assert.equal(res.status, 200, "sem GC o bot ainda entrega mensagens");
  });

  test("responde 503 somente quando NÃO está logado", async () => {
    fake.loggedIn = false;
    fake.gcReady = true;

    const res = await post("/notify", { steamId: STEAM_ID, message: "oi" });
    assert.equal(res.status, 503);

    fake.loggedIn = true;
  });

  test("rejeita SteamID malformado com 400", async () => {
    const res = await post("/notify", { steamId: "123", message: "oi" });
    assert.equal(res.status, 400);
  });

  test("rejeita corpo sem message com 400", async () => {
    const res = await post("/notify", { steamId: STEAM_ID });
    assert.equal(res.status, 400);
  });

  test("propaga falha de entrega como 502", async () => {
    fake.falharEnvio = true;
    const res = await post("/notify", { steamId: STEAM_ID, message: "oi" });
    assert.equal(res.status, 502, "falha de entrega não pode ser reportada como sucesso");
    fake.falharEnvio = false;
  });
});

describe("GET /health", () => {
  test("saúde depende do login, não do GC", async () => {
    fake.loggedIn = true;
    fake.gcReady = false;

    const res = await fetch(base + "/health");
    assert.equal(res.status, 200, "sem GC o serviço continua saudável: ainda entrega mensagens");

    const body = (await res.json()) as any;
    assert.equal(body.ready, true);
    assert.equal(body.gcReady, false, "gcReady é informativo e reportado à parte");
  });

  test("503 quando deslogado", async () => {
    fake.loggedIn = false;
    const res = await fetch(base + "/health");
    assert.equal(res.status, 503);
    fake.loggedIn = true;
  });
});

describe("POST /match-info", () => {
  /**
   * O backend usa o STATUS HTTP para decidir entre retentar e desistir.
   * Colapsar tudo em 404 fazia "GC fora do ar" ser tratado como
   * "partida não existe", descartando a partida para sempre.
   */
  test("503 quando o GC está indisponível (retentável)", async () => {
    fake.matchResult = { status: "GC_UNAVAILABLE" };
    const res = await post("/match-info", { shareCode: "CSGO-x", requesterSteamId: STEAM_ID });

    assert.equal(res.status, 503);
    assert.equal(res.headers.get("retry-after"), "60");
  });

  test("504 no timeout do GC (retentável)", async () => {
    fake.matchResult = { status: "TIMEOUT" };
    const res = await post("/match-info", { shareCode: "CSGO-x" });
    assert.equal(res.status, 504);
  });

  test("404 quando a partida não existe (terminal)", async () => {
    fake.matchResult = { status: "NOT_FOUND" };
    const res = await post("/match-info", { shareCode: "CSGO-x" });
    assert.equal(res.status, 404);
  });

  test("200 com os dados da partida", async () => {
    fake.matchResult = {
      status: "OK",
      matchInfo: {
        matchId: "1",
        matchTimeUnix: 1786151381,
        matchDuration: 2292,
        mapName: null,
        matchResult: "completed",
        roundsWon: 13,
        roundsLost: 8,
        teamScores: [13, 8],
        demoUrl: "http://replay202.valve.net/730/x.dem.bz2",
        players: [],
      },
    };

    const res = await post("/match-info", { shareCode: "CSGO-x" });
    assert.equal(res.status, 200);

    const body = (await res.json()) as any;
    assert.equal(body.matchInfo.roundsWon, 13);
  });

  test("400 sem shareCode", async () => {
    const res = await post("/match-info", {});
    assert.equal(res.status, 400);
  });
});
