package com.countatic.core.exception;

/**
 * Falha temporária ao falar com a API da Valve (5xx, timeout, rede).
 *
 * <p>Falha <b>transitória</b>: o jogador continua elegível e a consulta deve
 * ser refeita no próximo ciclo.</p>
 */
public class ValveTransientException extends RuntimeException {

    public ValveTransientException(String message) {
        super(message);
    }

    public ValveTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
