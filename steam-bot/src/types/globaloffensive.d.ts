declare module "globaloffensive" {
  import SteamUser from "steam-user";

  class GlobalOffensive {
    constructor(steamUser: SteamUser);

    haveGCSession: boolean;
    accountData: any;

    requestGame(shareCodeOrDetails: string | { matchId: string; outcomeId: string; token: number }): void;

    /** Devolve `false` se o SteamID for inválido; senão dispara a consulta. */
    requestPlayersProfile(steamid: string, callback?: (profile: any) => void): boolean | void;

    on(event: "connectedToGC", callback: () => void): this;
    on(event: "disconnectedFromGC", callback: (reason: any) => void): this;
    on(event: "matchList", callback: (matches: any[], deSerializedResponse: any) => void): this;
    on(event: "error", callback: (err: any) => void): this;
    // Emitidos por globaloffensive mas não documentados: são a única
    // observabilidade do handshake ClientHello -> ClientWelcome.
    on(event: "debug", callback: (msg: string) => void): this;
    on(event: "connectionStatus", callback: (status: number, proto: any) => void): this;
    on(event: "accountData", callback: (proto: any) => void): this;

    once(event: "connectedToGC", callback: () => void): this;
    once(event: "disconnectedFromGC", callback: (reason: any) => void): this;
    once(event: "matchList", callback: (matches: any[], deSerializedResponse: any) => void): this;
    once(event: "error", callback: (err: any) => void): this;

    removeListener(event: string, callback: (...args: any[]) => void): this;
  }

  export = GlobalOffensive;
}
