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
   * Body: { shareCode: "CSGO-xxxxx-...", requesterSteamId?: "765611..." }
   *
   * O `requesterSteamId` é opcional mas recomendado: sem ele o placar
   * (roundsWon/roundsLost) não tem como ser orientado e sai na ordem bruta
   * do GC, ficando invertido para o time B.
   *
   * O status HTTP distingue falha transitória de terminal — o backend usa
   * isso para decidir entre retentar e desistir:
   *   200 OK        → partida encontrada
   *   503           → GC sem sessão (retentável)
   *   504           → GC não respondeu no prazo (retentável)
   *   404           → partida não existe / fora da janela de retenção (terminal)
   *   400/500       → share code inválido ou erro ao parsear (terminal)
   */
  router.post("/match-info", async (req: Request, res: Response) => {
    const { shareCode, requesterSteamId } = req.body as {
      shareCode?: string;
      requesterSteamId?: string;
    };

    if (!shareCode) {
      res.status(400).json({
        success: false,
        code: "MISSING_SHARE_CODE",
        error: "Campo 'shareCode' é obrigatório.",
      });
      return;
    }

    logger.info(`📋 Requisição de match-info recebida: ${shareCode}`);

    try {
      const result = await steamClient.requestMatchInfo(shareCode, requesterSteamId);

      switch (result.status) {
        case "OK":
          res.status(200).json({ success: true, matchInfo: result.matchInfo });
          return;

        case "GC_UNAVAILABLE":
          res.setHeader("Retry-After", "60");
          res.status(503).json({
            success: false,
            code: "GC_UNAVAILABLE",
            error:
              "Sem sessão com o Game Coordinator do CS2. " +
              "Verifique se a conta do bot possui CS2 (appid 730) na biblioteca.",
            shareCode,
          });
          return;

        case "TIMEOUT":
          res.setHeader("Retry-After", "60");
          res.status(504).json({
            success: false,
            code: "GC_TIMEOUT",
            error: "O Game Coordinator não respondeu no prazo.",
            shareCode,
          });
          return;

        case "NOT_FOUND":
          res.status(404).json({
            success: false,
            code: "MATCH_NOT_FOUND",
            error:
              "Partida não encontrada no Game Coordinator " +
              "(provavelmente fora da janela de retenção da Valve).",
            shareCode,
          });
          return;

        case "PARSE_ERROR":
          res.status(500).json({
            success: false,
            code: "PARSE_ERROR",
            error: result.error,
            shareCode,
          });
          return;
      }
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      logger.error(`Erro ao consultar match-info: ${error}`);
      res.status(500).json({ success: false, code: "INTERNAL_ERROR", error, shareCode });
    }
  });

  return router;
}
