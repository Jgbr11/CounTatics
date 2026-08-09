package com.countatic.core.exception;

/**
 * A API da Valve rejeitou as credenciais da requisição.
 *
 * <p>Falha <b>permanente para este jogador</b>: significa Steam Web API Key
 * inválida ou Game Authentication Code errado/revogado. Retentar não resolve
 * — só gera erro 403 no log a cada ciclo do scheduler, para sempre.</p>
 *
 * <p>O tratamento correto é desabilitar o auto-fetch do jogador e avisá-lo.</p>
 */
public class ValveAuthException extends RuntimeException {

    private final String steamId64;

    public ValveAuthException(String steamId64, String message) {
        super(message);
        this.steamId64 = steamId64;
    }

    public String getSteamId64() {
        return steamId64;
    }
}
