import SteamUser from "steam-user";
import SteamID from "steamid";
import GlobalOffensive from "globaloffensive";
import { ShareCode } from "globaloffensive-sharecode";
import fs from "fs";
import path from "path";
import { config } from "../config";
import logger from "../utils/logger";
import { parseGcMatch } from "./matchInfoParser";
import type { GCMatchInfo, MatchInfoResult, SendMessageResult } from "./types";

// Reexporta os contratos para não quebrar quem já importava daqui.
export type { GCMatchInfo, GCPlayerStats, MatchInfoResult, SendMessageResult } from "./types";

/** AppID do Counter-Strike 2. */
export const CS2_APPID = 730;

/** Nomes legíveis para o enum GCConnectionStatus do Game Coordinator. */
const GC_CONNECTION_STATUS: Record<number, string> = {
  0: "HAVE_SESSION",
  1: "GC_GOING_DOWN",
  2: "NO_SESSION",
  3: "NO_SESSION_IN_LOGON_QUEUE",
  4: "NO_STEAM",
};

/**
 * Status do ciclo de vida do LOGIN na Steam.
 *
 * NÃO descreve o Game Coordinator — o estado do GC vive em `gcReady` e é
 * exposto por `isGcReady()`. Manter os dois separados é obrigatório: o envio
 * de mensagens depende só do login, e não pode quebrar quando o GC conecta.
 */
export enum SteamClientStatus {
  DISCONNECTED = "DISCONNECTED",
  CONNECTING = "CONNECTING",
  WAITING_STEAM_GUARD = "WAITING_STEAM_GUARD",
  LOGGED_IN = "LOGGED_IN",
  ERROR = "ERROR",
}

interface PendingMatchRequest {
  shareCode: string;
  requesterSteamId?: string;
  resolve: (result: MatchInfoResult) => void;
  timer: NodeJS.Timeout;
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
  private gcWatchdog: NodeJS.Timeout | null = null;
  private gcRetryAttempts = 0;
  private readonly maxGcRetries = 20;
  private readonly gcWatchdogIntervalMs = 60000;
  private readonly pendingMatchRequests = new Map<string, PendingMatchRequest>();
  private readonly gcRequestTimeoutMs = 15000;
  private readonly gcMinRequestIntervalMs = 3000;
  private lastGcRequestAt = 0;
  private gcRequestChain: Promise<void> = Promise.resolve();
  private readonly tokenFilePath =
    process.env.TOKEN_FILE ?? path.join(process.cwd(), "refreshToken.txt");

  constructor() {
    this.client = new SteamUser({
      autoRelogin: true,
      renewRefreshTokens: true,
    });

    this.csgo = new GlobalOffensive(this.client);
    this.registerEventHandlers();
    this.registerGCEventHandlers();
    this.registerMatchListHandler();
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
      logger.info(`🎮 Lançando CS2 (appid ${CS2_APPID}) para conectar ao Game Coordinator...`);
      this.gcRetryAttempts = 0;
      this.ensureGamePlayed();
      this.startGcWatchdog();
    });

    // ─── Convite de amizade ──────────────────────────────────────
    // `chat.sendFriendMessage` só funciona para amigos aceitos. Sem isto,
    // todo usuário novo falha no último salto com um erro que parece bug do bot.
    this.client.on("friendRelationship", (steamId: any, relationship: number) => {
      if (relationship === SteamUser.EFriendRelationship.RequestRecipient) {
        logger.info(`👋 Convite de amizade recebido de ${steamId}. Aceitando...`);
        this.client.addFriend(steamId);
      }
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
    // IMPORTANTE: o estado do GC vive EXCLUSIVAMENTE em `this.gcReady`.
    // `this.status` descreve apenas o ciclo de vida do LOGIN na Steam.
    // Misturar os dois fazia `isReady()`/`sendMessage` falharem no exato
    // momento em que o GC conectava (status virava GC_CONNECTED != LOGGED_IN),
    // derrubando todo o envio de mensagens com 503.
    this.csgo.on("connectedToGC", () => {
      this.gcReady = true;
      this.gcRetryAttempts = 0;
      logger.info("🎮 Conectado ao Game Coordinator do CS2!");
      logger.info("   Pronto para consultar informações de partidas.");
    });

    this.csgo.on("disconnectedFromGC", (reason: any) => {
      this.gcReady = false;
      logger.warn(`⚠️ Desconectado do Game Coordinator do CS2 (reason: ${reason})`);
    });

    this.csgo.on("error", (err: any) => {
      logger.error(`❌ Erro no Game Coordinator: ${err}`);
    });

    // ─── Observabilidade do GC ───────────────────────────────────
    // Sem estes handlers o loop de ClientHello roda invisível: nenhum
    // erro é emitido quando a conta não tem licença do appid 730, então
    // a falha aparece só como "connectedToGC nunca dispara".
    this.csgo.on("debug", (msg: string) => {
      logger.debug(`[GC] ${msg}`);
    });

    this.csgo.on("connectionStatus", (status: number, proto: any) => {
      logger.info(
        `[GC] Connection status: ${GC_CONNECTION_STATUS[status] ?? status}` +
          (proto?.wait_seconds ? ` (wait ${proto.wait_seconds}s)` : "")
      );
    });

    this.csgo.on("accountData", (proto: any) => {
      logger.info(
        `[GC] accountData recebido — level=${proto?.player_level ?? "?"} ` +
          `vac_banned=${proto?.vac_banned ?? "?"} penalty=${proto?.penalty_seconds ?? 0}s`
      );
    });
  }

  /**
   * (Re)lança o CS2 para forçar uma nova tentativa de handshake com o GC.
   *
   * O `globaloffensive` só reage à *transição* do evento `appLaunched`. Depois de
   * um erro no steam-user, `globaloffensive/index.js:96` zera `_isInCSGO` mas o
   * steam-user continua achando que 730 está rodando — então reenviar apenas
   * `gamesPlayed([730])` não emite nada e o GC nunca mais é tentado.
   * Zerar antes força a transição.
   */
  private ensureGamePlayed(): void {
    this.client.gamesPlayed([]);
    setTimeout(() => {
      if (this.status === SteamClientStatus.LOGGED_IN) {
        this.client.gamesPlayed([CS2_APPID]);
      }
    }, 500);
  }

  /**
   * Watchdog: enquanto estivermos logados mas sem sessão de GC, refaz o
   * handshake periodicamente. Para após `maxGcRetries` para não martelar
   * a Steam eternamente quando a conta simplesmente não tem CS2.
   */
  private startGcWatchdog(): void {
    if (this.gcWatchdog) return;

    this.gcWatchdog = setInterval(() => {
      if (this.status !== SteamClientStatus.LOGGED_IN || this.gcReady) return;

      if (this.gcRetryAttempts >= this.maxGcRetries) {
        if (this.gcRetryAttempts === this.maxGcRetries) {
          this.gcRetryAttempts++; // loga uma única vez
          logger.error("═════════════════════════════════════════════════════════════════");
          logger.error("🛑 O Game Coordinator do CS2 não respondeu após várias tentativas.");
          logger.error(`   Causa mais provável: a conta '${config.steam.username}' NÃO possui`);
          logger.error("   Counter-Strike 2 (appid 730) na biblioteca.");
          logger.error("   Sem licença do 730 o GC ignora o ClientHello silenciosamente.");
          logger.error("   👉 Logue na conta do bot e instale o CS2 (gratuito), depois reinicie.");
          logger.error("═════════════════════════════════════════════════════════════════");
        }
        return;
      }

      this.gcRetryAttempts++;
      logger.warn(
        `🎮 GC ainda sem sessão — refazendo handshake ` +
          `(tentativa ${this.gcRetryAttempts}/${this.maxGcRetries})...`
      );
      this.ensureGamePlayed();
    }, this.gcWatchdogIntervalMs);
  }

  /**
   * Listener ÚNICO e persistente de `matchList`.
   *
   * Antes havia um `once` por requisição, com dois defeitos: (1) nunca era
   * removido no timeout, vazando um listener que depois resolvia uma
   * requisição alheia; (2) não era chaveado, então duas consultas
   * concorrentes se cruzavam — a primeira registrada ficava com a primeira
   * resposta que chegasse, fosse ela qual fosse.
   *
   * O GC ecoa o `matchid` em `CDataGCCStrike15_v2_MatchInfo`, então dá para
   * casar exatamente com o matchId decodificado do share code.
   */
  private registerMatchListHandler(): void {
    this.csgo.on("matchList", (matches: any[], _deSerializedResponse: any) => {
      if (!matches || matches.length === 0) {
        logger.warn("[GC] matchList vazio recebido (partida fora da janela de retenção?)");
        return;
      }

      for (const match of matches) {
        const matchId = String(match?.matchid ?? "");
        const pending = this.pendingMatchRequests.get(matchId);

        if (!pending) {
          logger.debug(`[GC] matchList para ${matchId} sem requisição pendente — ignorado.`);
          continue;
        }

        clearTimeout(pending.timer);
        this.pendingMatchRequests.delete(matchId);

        void this.parseMatchInfo(match, pending.shareCode, pending.requesterSteamId)
          .then((matchInfo) => pending.resolve({ status: "OK", matchInfo }))
          .catch((err) => {
            logger.error(`Erro ao parsear resposta do GC: ${err}`);
            pending.resolve({ status: "PARSE_ERROR", error: String(err) });
          });
      }
    });
  }

  /**
   * Solicita informações de uma partida ao Game Coordinator do CS2.
   *
   * Devolve um resultado discriminado para que o chamador consiga distinguir
   * "GC indisponível" (retentável) de "partida não encontrada" (terminal).
   * Antes tudo virava `null` e o backend não tinha como decidir se retentava.
   */
  async requestMatchInfo(
    shareCode: string,
    requesterSteamId?: string
  ): Promise<MatchInfoResult> {
    if (!this.gcReady) {
      logger.error("GC do CS2 não está conectado. Não é possível consultar match info.");
      return { status: "GC_UNAVAILABLE" };
    }

    let matchId: string;
    try {
      const decoded = new ShareCode(shareCode).decode();
      if (!decoded || !decoded.matchId) {
        return { status: "PARSE_ERROR", error: `Share code inválido: '${shareCode}'` };
      }
      matchId = String(decoded.matchId);
    } catch (err) {
      return { status: "PARSE_ERROR", error: `Share code inválido: '${shareCode}' (${err})` };
    }

    // O GC descarta rajadas de requestGame em silêncio — sem resposta e sem
    // erro, o que é indistinguível de timeout. Serializar com um intervalo
    // mínimo entre chamadas evita diagnosticar rate limit como "GC morto".
    await this.throttleGcRequest();

    if (!this.gcReady) return { status: "GC_UNAVAILABLE" };

    logger.info(`🔍 Solicitando informações da partida ao GC: ${shareCode} (matchId=${matchId})`);

    return new Promise<MatchInfoResult>((resolve) => {
      const timer = setTimeout(() => {
        this.pendingMatchRequests.delete(matchId);
        logger.warn(`⏰ Timeout ao aguardar resposta do GC para ${shareCode}`);
        resolve({ status: "TIMEOUT" });
      }, this.gcRequestTimeoutMs);

      this.pendingMatchRequests.set(matchId, {
        shareCode,
        requesterSteamId,
        resolve,
        timer,
      });

      try {
        this.csgo.requestGame(shareCode);
      } catch (err) {
        clearTimeout(timer);
        this.pendingMatchRequests.delete(matchId);
        resolve({ status: "PARSE_ERROR", error: `requestGame falhou: ${err}` });
      }
    });
  }

  /**
   * Consulta o perfil de um jogador no Game Coordinator.
   *
   * É a fonte do CS Rating (Premier) usado para comparar o desempenho contra
   * jogadores de nível parecido. A resposta da partida (`requestGame`) NÃO traz
   * ranking em CS2 — `reservation.rankings` chega vazio —, então o rating
   * precisa ser buscado por aqui.
   *
   * @returns o perfil, ou `null` se o GC não responder no prazo
   */
  async requestPlayerProfile(steamId64: string): Promise<any | null> {
    if (!this.gcReady) return null;

    await this.throttleGcRequest();
    if (!this.gcReady) return null;

    return new Promise((resolve) => {
      let concluido = false;

      const timer = setTimeout(() => {
        if (concluido) return;
        concluido = true;
        logger.warn(`⏰ Timeout ao consultar o perfil de ${steamId64} no GC.`);
        resolve(null);
      }, this.gcRequestTimeoutMs);

      try {
        const enviado = this.csgo.requestPlayersProfile(steamId64, (profile: any) => {
          if (concluido) return;
          concluido = true;
          clearTimeout(timer);
          resolve(profile ?? null);
        });

        // requestPlayersProfile devolve false para SteamID inválido.
        if (enviado === false) {
          concluido = true;
          clearTimeout(timer);
          logger.warn(`SteamID inválido para consulta de perfil: ${steamId64}`);
          resolve(null);
        }
      } catch (err) {
        concluido = true;
        clearTimeout(timer);
        logger.error(`Erro ao consultar perfil de ${steamId64}: ${err}`);
        resolve(null);
      }
    });
  }

  /** Garante um intervalo mínimo entre chamadas consecutivas ao GC. */
  private async throttleGcRequest(): Promise<void> {
    const previous = this.gcRequestChain;
    let release!: () => void;
    this.gcRequestChain = new Promise<void>((r) => (release = r));

    await previous;
    const waited = Date.now() - this.lastGcRequestAt;
    if (waited < this.gcMinRequestIntervalMs) {
      await new Promise((r) => setTimeout(r, this.gcMinRequestIntervalMs - waited));
    }
    this.lastGcRequestAt = Date.now();
    release();
  }

  /**
   * Converte a resposta crua do GC no contrato consumido pelo core-backend.
   *
   * A conversão em si vive em `matchInfoParser` — lógica pura, sem sessão da
   * Steam, para poder ser testada diretamente. Foi ali que um bug de
   * alinhamento de índices atribuiu estatísticas ao jogador errado.
   */
  private async parseMatchInfo(
    match: any,
    shareCode: string,
    requesterSteamId?: string
  ): Promise<GCMatchInfo> {
    logger.info(`✅ Informações da partida recebidas do GC! (${shareCode})`);

    const { matchInfo, warnings } = await parseGcMatch(
      match,
      (ids) => this.resolvePersonaNames(ids),
      requesterSteamId
    );

    for (const w of warnings) {
      logger.warn(`[GC] ${shareCode}: ${w.message}`);
    }

    logger.info(
      `   Mapa: ${matchInfo.mapName ?? "(não informado pelo GC)"} | ` +
        `Placar: ${matchInfo.roundsWon}-${matchInfo.roundsLost} | ` +
        `Jogadores: ${matchInfo.players.length} | Demo: ${matchInfo.demoUrl ? "sim" : "não"}`
    );

    return matchInfo;
  }

  /**
   * Resolve os nomes reais dos jogadores via personas da Steam.
   * Falha de forma silenciosa — o chamador cai no nome posicional.
   */
  private resolvePersonaNames(steamIds: string[]): Promise<Map<string, string>> {
    const valid = steamIds.filter((id) => id && id !== "0");
    if (valid.length === 0) return Promise.resolve(new Map());

    return new Promise((resolve) => {
      const names = new Map<string, string>();
      const timer = setTimeout(() => {
        logger.warn("[GC] Timeout ao resolver personas — usando nomes posicionais.");
        resolve(names);
      }, 5000);

      try {
        // getPersonas devolve uma Promise ALÉM de chamar o callback; se um dos
        // perfis não responder no prazo interno de 10 s ela rejeita, então o
        // .catch é obrigatório para não gerar unhandledRejection.
        const maybePromise = this.client.getPersonas(
          valid,
          (err: Error | null, personas: Record<string, any>) => {
            clearTimeout(timer);
            if (err) {
              logger.warn(`[GC] getPersonas retornou erro: ${err.message}`);
              resolve(names);
              return;
            }
            for (const [sid, persona] of Object.entries(personas ?? {})) {
              if (persona?.player_name) names.set(sid, persona.player_name);
            }
            resolve(names);
          }
        ) as unknown as Promise<unknown> | undefined;

        if (maybePromise && typeof maybePromise.catch === "function") {
          maybePromise.catch(() => {
            /* já tratado pelo callback / timeout acima */
          });
        }
      } catch (err) {
        clearTimeout(timer);
        logger.warn(`[GC] getPersonas falhou: ${err}`);
        resolve(names);
      }
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
    // Depende SÓ do login. O GC é irrelevante para enviar chat.
    if (!this.isLoggedIn()) {
      return {
        success: false,
        steamId: steamId64,
        error: `Steam Client não está logado (status: ${this.status}). Aguarde a autenticação.`,
      };
    }

    const steamID = new SteamID(steamId64);

    // A Steam limita a frequência de mensagens de chat. Enviar o relatório
    // básico e o profundo em sequência (o que é o fluxo normal quando a demo
    // é analisada) estoura o limite e a segunda mensagem se perde.
    // Backoff: 5s, 15s, 45s.
    const backoffMs = [5000, 15000, 45000];

    for (let attempt = 0; attempt <= backoffMs.length; attempt++) {
      try {
        logger.info(`📤 Enviando mensagem para ${steamId64}...`);
        await this.client.chat.sendFriendMessage(steamID, message);

        logger.info(`✅ Mensagem enviada com sucesso para ${steamId64}`);
        return { success: true, steamId: steamId64 };
      } catch (err) {
        const error = err instanceof Error ? err.message : String(err);
        const isRateLimit = /RateLimitExceeded/i.test(error);

        if (isRateLimit && attempt < backoffMs.length) {
          const waitMs = backoffMs[attempt];
          logger.warn(
            `⏳ Steam aplicou rate limit ao enviar para ${steamId64}. ` +
              `Aguardando ${waitMs / 1000}s (tentativa ${attempt + 1}/${backoffMs.length})...`
          );
          await new Promise((r) => setTimeout(r, waitMs));
          continue;
        }

        logger.error(`❌ Falha ao enviar mensagem para ${steamId64}: ${error}`);
        return { success: false, steamId: steamId64, error };
      }
    }

    return {
      success: false,
      steamId: steamId64,
      error: "RateLimitExceeded — limite da Steam persistiu após todas as tentativas.",
    };
  }

  getStatus(): SteamClientStatus {
    return this.status;
  }

  /** Logado na Steam — é o único requisito para enviar mensagens de chat. */
  isLoggedIn(): boolean {
    return this.status === SteamClientStatus.LOGGED_IN;
  }

  /** Sessão ativa com o Game Coordinator do CS2 — requisito só para consultar partidas. */
  isGcReady(): boolean {
    return this.gcReady;
  }

  isWaitingSteamGuard(): boolean {
    return this.status === SteamClientStatus.WAITING_STEAM_GUARD;
  }

  getPendingGuardDomain(): string | null {
    return this.pendingGuardDomain;
  }

  shutdown(): void {
    logger.info("Encerrando Steam Client...");
    if (this.gcWatchdog) {
      clearInterval(this.gcWatchdog);
      this.gcWatchdog = null;
    }
    this.client.logOff();
    this.status = SteamClientStatus.DISCONNECTED;
    this.gcReady = false;
  }
}
