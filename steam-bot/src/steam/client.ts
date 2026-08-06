import SteamUser from "steam-user";
import { config } from "../config";
import logger from "../utils/logger";

/**
 * Status de conexão do Steam Client.
 */
export enum SteamClientStatus {
  DISCONNECTED = "DISCONNECTED",
  CONNECTING = "CONNECTING",
  CONNECTED = "CONNECTED",
  LOGGED_IN = "LOGGED_IN",
  ERROR = "ERROR",
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
 * Gerenciador do Steam Client.
 *
 * Encapsula toda a lógica de autenticação, gerenciamento de sessão,
 * reconexão automática e envio de mensagens via Steam Chat.
 *
 * Esta classe é um singleton — apenas uma instância do Steam Client
 * deve existir por processo, pois a API da Steam limita sessões
 * simultâneas por conta.
 */
export class SteamClientManager {
  private client: SteamUser;
  private status: SteamClientStatus = SteamClientStatus.DISCONNECTED;
  private reconnectAttempts = 0;
  private readonly maxReconnectAttempts = 5;
  private readonly reconnectDelayMs = 5000;
  private loginPromise: Promise<void> | null = null;

  constructor() {
    this.client = new SteamUser({
      autoRelogin: true,
      renewRefreshTokens: true,
    });

    this.registerEventHandlers();
  }

  /**
   * Registra os event handlers do Steam Client para gerenciar
   * o ciclo de vida da conexão.
   */
  private registerEventHandlers(): void {
    // ─── Login bem-sucedido ──────────────────────────────────────
    this.client.on("loggedOn", () => {
      this.status = SteamClientStatus.LOGGED_IN;
      this.reconnectAttempts = 0;
      logger.info("✅ Steam Client logado com sucesso!");
      logger.info(`   Conta: ${config.steam.username}`);
      logger.info(`   SteamID: ${this.client.steamID?.toString()}`);

      // Definir status como Online
      this.client.setPersona(SteamUser.EPersonaState.Online);
    });

    // ─── WebSession pronta (indica sessão completa) ──────────────
    this.client.on("webSession", (_sessionId: string, _cookies: string[]) => {
      logger.info("🌐 Web session estabelecida");
    });

    // ─── Erro de autenticação ────────────────────────────────────
    this.client.on("error", (err: Error & { eresult?: number }) => {
      this.status = SteamClientStatus.ERROR;
      logger.error(`❌ Erro no Steam Client: ${err.message}`);

      // EResult codes comuns:
      // 5  = InvalidPassword
      // 18 = AccountDisabled
      // 84 = RateLimitExceeded
      if (err.eresult === 5) {
        logger.error("   Senha incorreta. Verifique STEAM_PASSWORD no .env");
      } else if (err.eresult === 18) {
        logger.error("   Conta Steam desabilitada.");
      } else if (err.eresult === 84) {
        logger.error("   Rate limit atingido. Aguarde antes de tentar novamente.");
      }

      this.attemptReconnect();
    });

    // ─── Desconexão ──────────────────────────────────────────────
    this.client.on("disconnected", (eresult: number, msg?: string) => {
      this.status = SteamClientStatus.DISCONNECTED;
      logger.warn(`⚠️ Steam Client desconectado (eresult: ${eresult}, msg: ${msg || "N/A"})`);
      this.attemptReconnect();
    });

    // ─── Steam Guard (2FA) ───────────────────────────────────────
    this.client.on("steamGuard", (domain: string | null, callback: (code: string) => void) => {
      if (config.steam.sharedSecret) {
        // Se temos o shared secret, gerar o código automaticamente
        const SteamTotp = require("steam-totp");
        const code = SteamTotp.generateAuthCode(config.steam.sharedSecret);
        logger.info(`🔐 Steam Guard: código 2FA gerado automaticamente`);
        callback(code);
      } else if (domain) {
        // Email code — requer input manual
        logger.warn(`🔐 Steam Guard: código enviado para e-mail (*@${domain})`);
        logger.warn("   Configure STEAM_SHARED_SECRET para autenticação automática.");

        // Ler do stdin em modo interativo
        process.stdout.write("   Digite o código Steam Guard: ");
        process.stdin.once("data", (data) => {
          callback(data.toString().trim());
        });
      } else {
        // Mobile authenticator sem shared secret
        logger.warn("🔐 Steam Guard: código do autenticador mobile necessário");
        process.stdout.write("   Digite o código do autenticador: ");
        process.stdin.once("data", (data) => {
          callback(data.toString().trim());
        });
      }
    });

    // ─── Mensagem recebida (para debug) ──────────────────────────
    this.client.chat.on("friendMessage", (msg: { steamid_friend: any; message: string }) => {
      logger.debug(`📩 Mensagem recebida de ${msg.steamid_friend}: ${msg.message}`);
    });
  }

  /**
   * Tenta reconectar ao Steam após uma desconexão.
   * Usa backoff linear com limite de tentativas.
   */
  private attemptReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      logger.error(
        `🚫 Máximo de ${this.maxReconnectAttempts} tentativas de reconexão atingido. ` +
        `Reinicie o serviço manualmente.`
      );
      return;
    }

    this.reconnectAttempts++;
    const delay = this.reconnectDelayMs * this.reconnectAttempts;

    logger.info(
      `🔄 Tentativa de reconexão ${this.reconnectAttempts}/${this.maxReconnectAttempts} ` +
      `em ${delay / 1000}s...`
    );

    setTimeout(() => {
      this.login().catch((err) => {
        logger.error(`Falha na reconexão: ${err.message}`);
      });
    }, delay);
  }

  /**
   * Realiza o login no Steam.
   *
   * Retorna uma Promise que resolve quando o login é concluído.
   * Se já houver um login em andamento, retorna a mesma Promise
   * (evita logins duplicados).
   */
  async login(): Promise<void> {
    if (this.status === SteamClientStatus.LOGGED_IN) {
      logger.info("Steam Client já está logado.");
      return;
    }

    if (this.loginPromise) {
      logger.debug("Login já em andamento, aguardando...");
      return this.loginPromise;
    }

    this.status = SteamClientStatus.CONNECTING;
    logger.info(`🔑 Fazendo login no Steam como '${config.steam.username}'...`);

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

      this.client.logOn({
        accountName: config.steam.username,
        password: config.steam.password,
      });
    });

    return this.loginPromise;
  }

  /**
   * Envia uma mensagem de chat para um jogador via Steam.
   *
   * @param steamId64 SteamID64 do destinatário (string de 17 dígitos)
   * @param message Texto da mensagem a enviar
   * @returns Resultado do envio
   */
  async sendMessage(steamId64: string, message: string): Promise<SendMessageResult> {
    if (this.status !== SteamClientStatus.LOGGED_IN) {
      return {
        success: false,
        steamId: steamId64,
        error: `Steam Client não está logado (status: ${this.status}). ` +
               `Aguarde a conexão ou verifique as credenciais.`,
      };
    }

    try {
      logger.info(`📤 Enviando mensagem para ${steamId64}...`);
      logger.debug(`   Conteúdo (${message.length} chars): ${message.substring(0, 100)}...`);

      // steam-user / steamid instance
      const steamID = new (SteamUser as any).SteamID(steamId64);

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

  /**
   * Retorna o status atual da conexão Steam.
   */
  getStatus(): SteamClientStatus {
    return this.status;
  }

  /**
   * Verifica se o bot está pronto para enviar mensagens.
   */
  isReady(): boolean {
    return this.status === SteamClientStatus.LOGGED_IN;
  }

  /**
   * Desconecta o Steam Client graciosamente.
   */
  shutdown(): void {
    logger.info("Encerrando Steam Client...");
    this.client.logOff();
    this.status = SteamClientStatus.DISCONNECTED;
  }
}
