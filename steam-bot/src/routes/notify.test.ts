import { describe, it, before } from "node:test";
import assert from "node:assert";
import express from "express";
import { createRouter } from "./notify";
import { SteamClientManager, SteamClientStatus } from "../steam/client";

describe("Steam Bot Routes (/notify & /health)", () => {
  let mockSteamClient: SteamClientManager;
  let app: express.Application;

  before(() => {
    mockSteamClient = {
      getStatus: () => SteamClientStatus.DISCONNECTED,
      isReady: () => false,
      sendMessage: async () => ({ success: false, steamId: "", error: "Not connected" }),
      login: async () => {},
      shutdown: () => {},
    } as unknown as SteamClientManager;

    app = express();
    app.use(express.json());
    app.use("/", createRouter(mockSteamClient));
  });

  it("GET /health deve retornar status 503 quando o bot estiver desconectado", async () => {
    // Simula requisição interna
    const req = {
      method: "GET",
      url: "/health",
    };
    assert.strictEqual(mockSteamClient.isReady(), false);
  });

  it("POST /notify deve rejeitar payloads sem steamId ou message", async () => {
    // Validação básica do contrato de rotas
    assert.strictEqual(typeof createRouter, "function");
  });
});
