declare module "globaloffensive" {
  import SteamUser from "steam-user";

  class GlobalOffensive {
    constructor(steamUser: SteamUser);

    haveGCSession: boolean;

    requestGame(shareCodeOrDetails: string | { matchId: string; outcomeId: string; token: number }): void;

    on(event: "connectedToGC", callback: () => void): this;
    on(event: "disconnectedFromGC", callback: (reason: any) => void): this;
    on(event: "matchList", callback: (matches: any[], deSerializedResponse: any) => void): this;
    on(event: "error", callback: (err: any) => void): this;

    once(event: "connectedToGC", callback: () => void): this;
    once(event: "disconnectedFromGC", callback: (reason: any) => void): this;
    once(event: "matchList", callback: (matches: any[], deSerializedResponse: any) => void): this;
    once(event: "error", callback: (err: any) => void): this;
  }

  export = GlobalOffensive;
}
