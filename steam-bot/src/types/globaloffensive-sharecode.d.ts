declare module "globaloffensive-sharecode" {
  /** Componentes decodificados de um share code `CSGO-xxxxx-...`. */
  export interface DecodedShareCode {
    /** MatchID de 64 bits, em string decimal (o GC ecoa este valor). */
    matchId: string;
    /** OutcomeID de 64 bits, em string decimal. */
    outcomeId: string;
    /** Token de 16 bits, em string decimal. */
    token: string;
  }

  export class ShareCode {
    constructor(code: string);
    code: string | null;
    originalCode: string;
    /** Devolve `false` quando o share code é malformado. */
    decode(): DecodedShareCode | false;
  }
}
