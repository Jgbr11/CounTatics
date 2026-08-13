/**
 * Configuração centralizada do Steam Bot.
 *
 * A validação das variáveis de ambiente acontece sob demanda, na primeira
 * chamada a `getConfig()` — não no carregamento do módulo. Isso permite que
 * outros módulos importem tipos/enums daqui (ou de módulos que o importam
 * transitivamente) sem precisar de credenciais da Steam presentes, como em
 * testes que só exercitam rotas HTTP com dublês.
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
    throw new Error(
      "Variáveis de ambiente obrigatórias não definidas: STEAM_USERNAME e STEAM_PASSWORD. " +
      "Use .env.example como referência."
    );
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

let cached: BotConfig | null = null;

/**
 * Configuração validada, carregada na primeira chamada.
 *
 * Antes isto era `export const config = loadConfig()`, avaliado no import.
 * Qualquer módulo que alcançasse `config` — inclusive um arquivo de teste que
 * só queria um enum de `client.ts` — derrubava o processo se as credenciais da
 * Steam não estivessem no ambiente. Testar rotas HTTP com dublês não exige
 * credencial nenhuma; a validação precisa acontecer quando o bot vai de fato
 * logar, não quando o módulo é lido.
 */
export function getConfig(): BotConfig {
  if (cached === null) {
    cached = loadConfig();
  }
  return cached;
}

export type { BotConfig };
