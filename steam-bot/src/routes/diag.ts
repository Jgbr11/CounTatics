import { Router, Request, Response } from "express";
import { SteamClientManager } from "../steam/client";
import logger from "../utils/logger";

/**
 * Rotas de diagnóstico do Game Coordinator.
 *
 * Servem para inspecionar o que a Valve realmente devolve — nem tudo que está
 * no protobuf vem preenchido em CS2, e a única forma confiável de saber é
 * consultar de verdade.
 */
export function createDiagRoutes(steamClient: SteamClientManager): Router {
  const router = Router();

  /**
   * GET /diag/profile/:steamId
   *
   * Devolve o perfil cru do jogador no GC, incluindo `ranking` — a fonte do
   * CS Rating usado para comparar desempenho contra jogadores de nível
   * parecido, em vez de contra uma média global sem significado.
   */
  router.get("/diag/profile/:steamId", async (req: Request, res: Response) => {
    const steamId = String(req.params.steamId);

    if (!steamClient.isGcReady()) {
      res.status(503).json({ error: "Sem sessão com o Game Coordinator." });
      return;
    }

    try {
      const profile = await steamClient.requestPlayerProfile(steamId);
      if (!profile) {
        res.status(504).json({ error: "O GC não respondeu no prazo.", steamId });
        return;
      }
      res.json({ steamId, profile });
    } catch (err) {
      logger.error(`Erro ao consultar perfil de ${steamId}: ${err}`);
      res.status(500).json({ error: String(err) });
    }
  });

  return router;
}
