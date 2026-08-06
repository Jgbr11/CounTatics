import { Router, Request, Response } from "express";
import { SteamClientManager } from "../steam/client";
import logger from "../utils/logger";

/**
 * Payload esperado no POST /notify.
 * Enviado pelo Core Backend (Java) após calcular as métricas.
 */
interface NotifyPayload {
  /** SteamID64 do jogador destinatário (17 dígitos). */
  steamId: string;

  /** Mensagem formatada com os insights e dicas de melhoria. */
  message: string;

  /** (Opcional) ID da partida para rastreabilidade. */
  matchId?: number;

  /** (Opcional) Nome do jogador para logging. */
  playerName?: string;
}

/**
 * Cria o router Express com os endpoints do Steam Bot.
 *
 * @param steamClient instância do gerenciador do Steam Client
 * @returns Express Router configurado
 */
export function createRouter(steamClient: SteamClientManager): Router {
  const router = Router();

  // ─── POST /notify ──────────────────────────────────────────────
  // Recebe o payload do Core Backend e envia a mensagem via Steam Chat.
  router.post("/notify", async (req: Request, res: Response) => {
    const payload = req.body as NotifyPayload;

    // ─── Validação do payload ──────────────────────────────────
    if (!payload.steamId || !payload.message) {
      logger.warn("Payload inválido recebido em /notify: faltando steamId ou message");
      res.status(400).json({
        success: false,
        error: "Campos obrigatórios: 'steamId' (string) e 'message' (string)",
      });
      return;
    }

    // Validar formato do SteamID64 (17 dígitos numéricos)
    if (!/^\d{17}$/.test(payload.steamId)) {
      logger.warn(`SteamID inválido recebido: ${payload.steamId}`);
      res.status(400).json({
        success: false,
        error: `SteamID inválido: '${payload.steamId}'. Deve ser um SteamID64 com 17 dígitos.`,
      });
      return;
    }

    // ─── Verificar se o bot está conectado ──────────────────────
    if (!steamClient.isReady()) {
      const status = steamClient.getStatus();
      logger.error(`Bot não está pronto para enviar mensagens (status: ${status})`);
      res.status(503).json({
        success: false,
        error: `Steam Bot não está conectado (status: ${status}). Tente novamente em instantes.`,
        status,
      });
      return;
    }

    // ─── Enviar mensagem ────────────────────────────────────────
    logger.info(
      `📨 Notificação recebida: enviar para ${payload.playerName || payload.steamId}` +
      (payload.matchId ? ` (match #${payload.matchId})` : "")
    );

    const result = await steamClient.sendMessage(payload.steamId, payload.message);

    if (result.success) {
      res.status(200).json({
        success: true,
        message: `Mensagem enviada com sucesso para ${payload.steamId}`,
        steamId: payload.steamId,
      });
    } else {
      res.status(502).json({
        success: false,
        error: result.error,
        steamId: payload.steamId,
      });
    }
  });

  // ─── GET /health ───────────────────────────────────────────────
  router.get("/health", (_req: Request, res: Response) => {
    const status = steamClient.getStatus();
    const isReady = steamClient.isReady();

    res.status(isReady ? 200 : 503).json({
      service: "countatic-steam-bot",
      status,
      ready: isReady,
      timestamp: new Date().toISOString(),
    });
  });

  // ─── GET /status ───────────────────────────────────────────────
  // Endpoint detalhado de status para monitoramento.
  router.get("/status", (_req: Request, res: Response) => {
    res.status(200).json({
      service: "countatic-steam-bot",
      steamStatus: steamClient.getStatus(),
      ready: steamClient.isReady(),
      uptime: process.uptime(),
      memory: process.memoryUsage(),
      timestamp: new Date().toISOString(),
    });
  });

  return router;
}
