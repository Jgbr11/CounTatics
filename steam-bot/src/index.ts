import dotenv from "dotenv";
// Carregar .env ANTES de qualquer outro import que use process.env
dotenv.config();

import express from "express";
import { config } from "./config";
import { SteamClientManager } from "./steam/client";
import { createRouter } from "./routes/notify";
import { createMatchRoutes } from "./routes/match";
import { createDiagRoutes } from "./routes/diag";
import logger from "./utils/logger";

/**
 * Entry point do CounTatic Steam Bot.
 *
 * Fluxo de inicialização:
 * 1. Carregar variáveis de ambiente (.env)
 * 2. Validar configuração (STEAM_USERNAME, STEAM_PASSWORD)
 * 3. Conectar ao Steam via steam-user
 * 4. Iniciar servidor Express (POST /notify, GET /health)
 * 5. Aguardar webhooks do Core Backend (Java)
 */
async function main(): Promise<void> {
  logger.info("═══════════════════════════════════════════════");
  logger.info("  CounTatic Steam Bot — CS2 Notification Service");
  logger.info("═══════════════════════════════════════════════");

  // ─── 1. Inicializar Steam Client ────────────────────────────────
  const steamClient = new SteamClientManager();

  try {
    await steamClient.login();
    logger.info("Steam Client conectado e pronto para enviar mensagens.");
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    logger.error(`Falha no login inicial do Steam: ${error}`);
    logger.warn("O servidor HTTP será iniciado mesmo assim.");
    logger.warn("O bot tentará reconectar automaticamente.");
  }

  // ─── 2. Configurar Express ──────────────────────────────────────
  const app = express();

  // Middleware: Parse JSON bodies
  app.use(express.json({ limit: "1mb" }));

  // Middleware: Request logging
  app.use((req, _res, next) => {
    logger.debug(`→ ${req.method} ${req.path} (${req.ip})`);
    next();
  });

  // Montar rotas
  app.use("/", createRouter(steamClient));
  app.use("/", createMatchRoutes(steamClient));
  app.use("/", createDiagRoutes(steamClient));

  // ─── 3. Iniciar servidor HTTP ───────────────────────────────────
  const port = config.server.port;
  const server = app.listen(port, () => {
    logger.info(`🚀 Servidor HTTP iniciado na porta ${port}`);
    logger.info(`   Endpoints disponíveis:`);
    logger.info(`   POST /notify      — Enviar mensagem para jogador`);
    logger.info(`   POST /match-info  — Consultar stats de partida via GC`);
    logger.info(`   GET  /health      — Health check do bot`);
    logger.info(`   GET  /status      — Status detalhado`);
    logger.info("");
    logger.info("Aguardando webhooks do Core Backend (Java)...");
  });

  // ─── 4. Graceful Shutdown ───────────────────────────────────────
  const shutdown = async (signal: string) => {
    logger.info(`\n${signal} recebido. Encerrando graciosamente...`);

    // Fechar servidor HTTP (parar de aceitar novas conexões)
    server.close(() => {
      logger.info("Servidor HTTP encerrado.");
    });

    // Desconectar do Steam
    steamClient.shutdown();

    logger.info("Steam Bot encerrado com sucesso. Até a próxima! 👋");
    process.exit(0);
  };

  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));

  // Capturar exceções não tratadas
  process.on("uncaughtException", (err) => {
    logger.error(`Exceção não tratada: ${err.message}`, { stack: err.stack });
  });

  process.on("unhandledRejection", (reason) => {
    logger.error(`Promise rejection não tratada: ${reason}`);
  });
}

// ─── Executar ─────────────────────────────────────────────────────
main().catch((err) => {
  console.error("Erro fatal na inicialização:", err);
  process.exit(1);
});
