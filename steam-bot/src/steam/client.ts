import SteamUser from "steam-user";
import SteamID from "steamid";
import GlobalOffensive from "globaloffensive";
import fs from "fs";
import path from "path";
import { config } from "../config";
import logger from "../utils/logger";

/**
 * Status de conexão do Steam Client.
 */
export enum SteamClientStatus {
  DISCONNECTED = "DISCONNECTED",
  CONNECTING = "CONNECTING",
  WAITING_STEAM_GUARD = "WAITING_STEAM_GUARD",
  CONNECTED = "CONNECTED",
  LOGGED_IN = "LOGGED_IN",
  GC_CONNECTED = "GC_CONNECTED",
  ERROR = "ERROR",
}

/**
 * Informações de uma partida retornadas pelo Game Coordinator do CS2.
 */
export interface GCMatchInfo {
  matchId: string;
  matchDuration: number;
  mapName: string;
  matchResult: string;
  roundsWon: number;
  roundsLost: number;
  demoUrl: string | null;
  players: GCPlayerStats[];
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
  team: string;
}

/**
 * Resultado do envio de uma mensagem via Steam Chat.
 */
export interface SendMessageResult {
  success: boolean;
  steamId: string;
  error?: string;
}

/**
 * Gerenciador do Steam Client com suporte a 2FA via HTTP e persistência de Refresh Token.
 */
export class SteamClientManager {
  private client: SteamUser;
  private csgo: GlobalOffensive;
  private gcReady: boolean = false;
  private status: SteamClientStatus = SteamClientStatus.DISCONNECTED;
  private reconnectAttempts = 0;
  private readonly maxReconnectAttempts = 5;
  private readonly reconnectDelayMs = 5000;
  private loginPromise: Promise<void> | null = null;
  private steamGuardCallback: ((code: string) => void) | null = null;
  private pendingGuardDomain: string | null = null;
  private readonly tokenFilePath = path.join(process.cwd(), "refreshToken.txt");

  constructor() {
    this.client = new SteamUser({
      autoRelogin: true,
      renewRefreshTokens: true,
    });

    this.csgo = new GlobalOffensive(this.client);
    this.registerEventHandlers();
    this.registerGCEventHandlers();
  }

  /**
   * Registra os event handlers do Steam Client para gerenciar o ciclo de vida.
   */
  private registerEventHandlers(): void {
    // ─── Login bem-sucedido ──────────────────────────────────────
    this.client.on("loggedOn", () => {
      this.status = SteamClientStatus.LOGGED_IN;
      this.reconnectAttempts = 0;
      this.steamGuardCallback = null;
      this.pendingGuardDomain = null;
      logger.info("✅ Steam Client logado com sucesso!");
      logger.info(`   Conta: ${config.steam.username}`);
      logger.info(`   SteamID: ${this.client.steamID?.toString()}`);

      this.client.setPersona(SteamUser.EPersonaState.Online);

      // Lançar o CS2 para conectar ao Game Coordinator
      logger.info("🎮 Lançando CS2 (appid 730) para conectar ao Game Coordinator...");
      this.client.gamesPlayed([730]);
    });

    // ─── Refresh Token gerado ───────────────────────────────────
    this.client.on("refreshToken", (token: string) => {
      logger.info("🔑 Refresh Token da Steam gerado/renovado com sucesso!");
      try {
        fs.writeFileSync(this.tokenFilePath, token, "utf-8");
        logger.info(`   Salvo em: ${this.tokenFilePath}`);
      } catch (err) {
        logger.error(`Erro ao salvar refreshToken.txt: ${err}`);
      }
    });

    // ─── WebSession pronta ───────────────────────────────────────
    this.client.on("webSession", (_sessionId: string, _cookies: string[]) => {
      logger.info("🌐 Web session estabelecida");
    });

    // ─── Erro de autenticação ────────────────────────────────────
    this.client.on("error", (err: Error & { eresult?: number }) => {
      // Se estiver aguardando Steam Guard, não muda para ERROR para não disparar reconnect
      if (this.status === SteamClientStatus.WAITING_STEAM_GUARD) {
        logger.warn(`⚠️ Steam Guard pendente (erro secundário: ${err.message})`);
        return;
      }

      this.status = SteamClientStatus.ERROR;
      logger.error(`❌ Erro no Steam Client: ${err.message}`);

      if (err.eresult === 5) {
        logger.error("   Senha incorreta. Verifique STEAM_PASSWORD no .env");
      } else if (err.eresult === 18) {
        logger.error("   Conta Steam desabilitada.");
      } else if (err.eresult === 84 || err.message.includes("AccountLoginDeniedThrottle")) {
        logger.error("🛑 Throttle da Steam ativado (AccountLoginDeniedThrottle).");
        logger.error("   Reconexões automáticas SUSPENSAS para não bloquear sua conta.");
        logger.error("   Quando receber o novo código no e-mail, envie via POST /steam-guard.");
        // Suspende reconexão automática
        this.reconnectAttempts = this.maxReconnectAttempts;
        return;
      }

      this.attemptReconnect();
    });

    // ─── Desconexão ──────────────────────────────────────────────
    this.client.on("disconnected", (eresult: number, msg?: string) => {
      if (this.status === SteamClientStatus.WAITING_STEAM_GUARD) {
        logger.debug("Desconexão temporária durante espera por Steam Guard (normal).");
        return;
      }

      this.status = SteamClientStatus.DISCONNECTED;
      logger.warn(`⚠️ Steam Client desconectado (eresult: ${eresult}, msg: ${msg || "N/A"})`);
      this.attemptReconnect();
    });

    // ─── Steam Guard (2FA) ───────────────────────────────────────
    this.client.on("steamGuard", (domain: string | null, callback: (code: string) => void) => {
      this.status = SteamClientStatus.WAITING_STEAM_GUARD;
      this.steamGuardCallback = callback;
      this.pendingGuardDomain = domain;

      if (config.steam.sharedSecret) {
        const SteamTotp = require("steam-totp");
        const code = SteamTotp.generateAuthCode(config.steam.sharedSecret);
        logger.info(`🔐 Steam Guard: código 2FA gerado automaticamente via shared secret`);
        callback(code);
      } else {
        const domainStr = domain ? `*@${domain}` : "mobile/e-mail";
        logger.warn(`═════════════════════════════════════════════════════════════════`);
        logger.warn(`🔐 STEAM GUARD NECESSÁRIO! Código enviado para ${domainStr}`);
        logger.warn(`👉 Envie o código do e-mail executando no PowerShell:`);
        logger.warn(`   Invoke-RestMethod -Uri "http://localhost:3000/steam-guard" -Method Post -ContentType "application/json" -Body '{"code":"SEU_CODIGO"}'`);
        logger.warn(`═════════════════════════════════════════════════════════════════`);
      }
    });

    // ─── Mensagem recebida ───────────────────────────────────────
    this.client.chat.on("friendMessage", (msg: { steamid_friend: any; message: string }) => {
      logger.debug(`📩 Mensagem recebida de ${msg.steamid_friend}: ${msg.message}`);
    });
  }

  /**
   * Registra os event handlers do Game Coordinator do CS2.
   */
  private registerGCEventHandlers(): void {
    this.csgo.on("connectedToGC", () => {
      this.gcReady = true;
      this.status = SteamClientStatus.GC_CONNECTED;
      logger.info("🎮 Conectado ao Game Coordinator do CS2!");
      logger.info("   Pronto para consultar informações de partidas.");
    });

    this.csgo.on("disconnectedFromGC", (reason: any) => {
      this.gcReady = false;
      if (this.status === SteamClientStatus.GC_CONNECTED) {
        this.status = SteamClientStatus.LOGGED_IN;
      }
      logger.warn(`⚠️ Desconectado do Game Coordinator do CS2 (reason: ${reason})`);
    });

    this.csgo.on("error", (err: any) => {
      logger.error(`❌ Erro no Game Coordinator: ${err}`);
    });
  }

  /**
   * Solicita informações de uma partida ao Game Coordinator do CS2 usando o share code.
   *
   * @param shareCode Share code completo (CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx)
   * @returns Promise com as informações da partida ou null se não encontrada
   */
  requestMatchInfo(shareCode: string): Promise<GCMatchInfo | null> {
    return new Promise((resolve) => {
      if (!this.gcReady) {
        logger.error("GC do CS2 não está conectado. Não é possível consultar match info.");
        resolve(null);
        return;
      }

      logger.info(`🔍 Solicitando informações da partida ao GC: ${shareCode}`);

      const timeout = setTimeout(() => {
        logger.warn(`⏰ Timeout ao aguardar resposta do GC para ${shareCode}`);
        resolve(null);
      }, 15000); // 15s timeout

      this.csgo.once("matchList", (matches: any[], _deSerializedResponse: any) => {
        clearTimeout(timeout);

        if (!matches || matches.length === 0) {
          logger.warn(`Nenhuma partida retornada pelo GC para ${shareCode}`);
          resolve(null);
          return;
        }

        const match = matches[0];
        logger.info(`✅ Informações da partida recebidas do GC!`);

        try {
          const roundstatsAll = match.roundstatsall || match.roundstats_legacy || [];
          const lastRoundStats = roundstatsAll.length > 0 ? roundstatsAll[roundstatsAll.length - 1] : (match.roundstats_legacy || null);

          // Extrair stats dos jogadores do último round (acumuladas)
          const players: GCPlayerStats[] = [];
          const reservations = match.roundstats_legacy?.reservation || match.watchablematchinfo || {};

          if (lastRoundStats && lastRoundStats.reservation) {
            const accountIds = lastRoundStats.reservation.account_ids || [];
            const kills = lastRoundStats.kills || [];
            const deaths = lastRoundStats.deaths || [];
            const assists = lastRoundStats.assists || [];
            const scores = lastRoundStats.scores || [];
            const mvps = lastRoundStats.mvps || [];
            const enemyHeadshots = lastRoundStats.enemy_headshots || [];

            for (let i = 0; i < accountIds.length; i++) {
              const accountId = accountIds[i];
              // Converter AccountID para SteamID64
              const steamId64 = accountId ? new SteamID(`[U:1:${accountId}]`).getSteamID64() : "0";

              players.push({
                steamId64,
                playerName: `Player${i + 1}`,
                kills: kills[i] || 0,
                deaths: deaths[i] || 0,
                assists: assists[i] || 0,
                headshots: enemyHeadshots[i] || 0,
                mvps: mvps[i] || 0,
                score: scores[i] || 0,
                team: i < 5 ? "CT" : "TR",
              });
            }
          }

          // Extrair informações da partida
          const mapName = match.watchablematchinfo?.game_map_group || 
                         match.watchablematchinfo?.game_map || 
                         "unknown";

          // Demo URL
          const demoUrl = match.roundstats_legacy?.map 
            ? `http://replay${match.roundstats_legacy.map}.valve.net/730/${match.roundstats_legacy.map}.dem.bz2`
            : null;

          const matchInfo: GCMatchInfo = {
            matchId: match.matchid?.toString() || "0",
            matchDuration: match.matchtime || 0,
            mapName: mapName.replace("mg_", "").replace("de_", "de_"),
            matchResult: "completed",
            roundsWon: lastRoundStats?.team_scores?.[0] || 0,
            roundsLost: lastRoundStats?.team_scores?.[1] || 0,
            demoUrl: demoUrl,
            players,
          };

          logger.info(`   Mapa: ${matchInfo.mapName} | Placar: ${matchInfo.roundsWon}-${matchInfo.roundsLost} | Jogadores: ${matchInfo.players.length}`);
          resolve(matchInfo);
        } catch (err) {
          logger.error(`Erro ao parsear resposta do GC: ${err}`);
          resolve(null);
        }
      });

      // Solicitar a partida ao GC usando o share code
      this.csgo.requestGame(shareCode);
    });
  }

  /**
   * Submete o código do Steam Guard recebido por e-mail via requisição HTTP.
   *
   * Se houver uma rotina de login aguardando o callback, ele o executa.
   * Caso contrário, força uma nova tentativa de login incluindo o authCode.
   *
   * @param code código de 5 dígitos recebido no e-mail
   * @return true
   */
  submitSteamGuardCode(code: string): boolean {
    const cleanCode = code.trim().toUpperCase();
    logger.info(`🔐 Processando código Steam Guard submetido via HTTP: '${cleanCode}'`);
    this.reconnectAttempts = 0;

    if (this.steamGuardCallback) {
      const cb = this.steamGuardCallback;
      this.steamGuardCallback = null;
      this.pendingGuardDomain = null;
      cb(cleanCode);
    } else {
      logger.info(`Forçando novo login com authCode '${cleanCode}'...`);
      this.loginWithAuthCode(cleanCode);
    }
    return true;
  }

  /**
   * Executa um login fornecendo o código de autenticação diretamente no payload.
   */
  private loginWithAuthCode(authCode: string): void {
    this.status = SteamClientStatus.CONNECTING;
    this.client.logOn({
      accountName: config.steam.username,
      password: config.steam.password,
      authCode: authCode,
    });
  }

  /**
   * Tenta reconectar ao Steam após uma desconexão.
   */
  private attemptReconnect(): void {
    // Não efetuar reconexão automática se estiver aguardando o usuário digitar o código 2FA
    if (this.status === SteamClientStatus.WAITING_STEAM_GUARD) {
      logger.debug("Aguardando entrada do código Steam Guard. Reconexão automática suspensa.");
      return;
    }

    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      logger.error(`🚫 Máximo de ${this.maxReconnectAttempts} tentativas de reconexão atingido.`);
      return;
    }

    this.reconnectAttempts++;
    const delay = this.reconnectDelayMs * this.reconnectAttempts;

    logger.info(`🔄 Tentativa de reconexão ${this.reconnectAttempts}/${this.maxReconnectAttempts} em ${delay / 1000}s...`);

    setTimeout(() => {
      this.login().catch((err) => {
        logger.error(`Falha na reconexão: ${err.message}`);
      });
    }, delay);
  }

  /**
   * Realiza o login no Steam (utiliza Refresh Token salvo se disponível).
   */
  async login(): Promise<void> {
    if (this.status === SteamClientStatus.LOGGED_IN) {
      logger.info("Steam Client já está logado.");
      return;
    }

    if (this.status === SteamClientStatus.WAITING_STEAM_GUARD) {
      logger.info("Steam Client está aguardando o código 2FA enviando por e-mail.");
      return;
    }

    if (this.loginPromise) {
      logger.debug("Login já em andamento, aguardando...");
      return this.loginPromise;
    }

    this.status = SteamClientStatus.CONNECTING;

    // Verificar se existe um Refresh Token salvo no disco
    let savedRefreshToken: string | undefined;
    if (fs.existsSync(this.tokenFilePath)) {
      try {
        savedRefreshToken = fs.readFileSync(this.tokenFilePath, "utf-8").trim();
      } catch (err) {
        logger.warn(`Não foi possível ler refreshToken.txt: ${err}`);
      }
    }

    if (savedRefreshToken) {
      logger.info(`🔑 Fazendo login no Steam usando Refresh Token salvo...`);
    } else {
      logger.info(`🔑 Fazendo login no Steam como '${config.steam.username}'...`);
    }

    this.loginPromise = new Promise<void>((resolve, reject) => {
      const onLoggedOn = () => {
        cleanup();
        this.loginPromise = null;
        resolve();
      };

      const onError = (err: Error) => {
        cleanup();
        this.loginPromise = null;
        reject(err);
      };

      const cleanup = () => {
        this.client.removeListener("loggedOn", onLoggedOn);
        this.client.removeListener("error", onError);
      };

      this.client.once("loggedOn", onLoggedOn);
      this.client.once("error", onError);

      if (savedRefreshToken) {
        this.client.logOn({
          refreshToken: savedRefreshToken,
        });
      } else {
        this.client.logOn({
          accountName: config.steam.username,
          password: config.steam.password,
        });
      }
    });

    return this.loginPromise;
  }

  /**
   * Envia uma mensagem de chat para um jogador via Steam.
   */
  async sendMessage(steamId64: string, message: string): Promise<SendMessageResult> {
    if (this.status !== SteamClientStatus.LOGGED_IN) {
      return {
        success: false,
        steamId: steamId64,
        error: `Steam Client não está logado (status: ${this.status}). Aguarde a autenticação.`,
      };
    }

    try {
      logger.info(`📤 Enviando mensagem para ${steamId64}...`);
      const steamID = new SteamID(steamId64);
      await this.client.chat.sendFriendMessage(steamID, message);

      logger.info(`✅ Mensagem enviada com sucesso para ${steamId64}`);
      return {
        success: true,
        steamId: steamId64,
      };
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      logger.error(`❌ Falha ao enviar mensagem para ${steamId64}: ${error}`);

      return {
        success: false,
        steamId: steamId64,
        error,
      };
    }
  }

  getStatus(): SteamClientStatus {
    return this.status;
  }

  isReady(): boolean {
    return this.status === SteamClientStatus.LOGGED_IN;
  }

  isWaitingSteamGuard(): boolean {
    return this.status === SteamClientStatus.WAITING_STEAM_GUARD;
  }

  getPendingGuardDomain(): string | null {
    return this.pendingGuardDomain;
  }

  shutdown(): void {
    logger.info("Encerrando Steam Client...");
    this.client.logOff();
    this.status = SteamClientStatus.DISCONNECTED;
  }
}
