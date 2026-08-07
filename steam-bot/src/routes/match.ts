import { Router, Request, Response } from "express";
import { SteamClientManager } from "../steam/client";
import logger from "../utils/logger";

/**
 * Rotas para consulta de informações de partidas via Game Coordinator do CS2.
 */
export function createMatchRoutes(steamClient: SteamClientManager): Router {
  const router = Router();

  /**
   * POST /match-info
   *
   * Consulta o Game Coordinator do CS2 para obter informações detalhadas
   * de uma partida usando o share code.
   *
   * Body: { shareCode: "CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx" }
   * Response: { success: true, matchInfo: { ... } }
   */
  router.post("/match-info", async (req: Request, res: Response) => {
    const { shareCode } = req.body;

    if (!shareCode) {
      res.status(400).json({
        success: false,
        error: "Campo 'shareCode' é obrigatório.",
      });
      return;
    }

    logger.info(`📋 Requisição de match-info recebida: ${shareCode}`);

    try {
      const matchInfo = await steamClient.requestMatchInfo(shareCode);

      if (!matchInfo) {
        res.status(404).json({
          success: false,
          error: "Informações da partida não encontradas no Game Coordinator.",
          shareCode,
        });
        return;
      }

      res.json({
        success: true,
        matchInfo,
      });
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      logger.error(`Erro ao consultar match-info: ${error}`);
      res.status(500).json({
        success: false,
        error,
      });
    }
  });

  return router;
}
