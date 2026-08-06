/**
 * Configuração centralizada do Steam Bot.
 * 
 * Todas as variáveis de ambiente são validadas aqui na inicialização.
 * Se alguma variável obrigatória estiver faltando, o processo encerra
 * imediatamente com uma mensagem clara.
 */

interface BotConfig {
  /** Credenciais da Steam */
  steam: {
    username: string;
    password: string;
    sharedSecret?: string;
  };

  /** Configuração do servidor HTTP */
  server: {
    port: number;
  };

  /** Logging */
  logLevel: string;
}

function loadConfig(): BotConfig {
  const username = process.env.STEAM_USERNAME;
  const password = process.env.STEAM_PASSWORD;

  if (!username || !password) {
    console.error(
      "═══════════════════════════════════════════════════════\n" +
      "  ERRO: Variáveis de ambiente obrigatórias não definidas!\n" +
      "  Defina STEAM_USERNAME e STEAM_PASSWORD no arquivo .env\n" +
      "  Use .env.example como referência.\n" +
      "═══════════════════════════════════════════════════════"
    );
    process.exit(1);
  }

  return {
    steam: {
      username,
      password,
      sharedSecret: process.env.STEAM_SHARED_SECRET || undefined,
    },
    server: {
      port: parseInt(process.env.BOT_PORT || "3000", 10),
    },
    logLevel: process.env.LOG_LEVEL || "info",
  };
}

export const config = loadConfig();
export type { BotConfig };
