package com.countatic.core.exception;

/**
 * O Game Coordinator do CS2 está temporariamente indisponível.
 *
 * <p>Falha <b>transitória</b>: o job deve ser reagendado, não abandonado.
 * Corresponde a HTTP 503 (bot sem sessão de GC) ou 504 (GC não respondeu
 * no prazo) vindos do endpoint {@code POST /match-info} do Steam Bot.</p>
 *
 * <p>Distinguir isto de "partida não encontrada" é essencial: antes ambos
 * viravam {@code null} e o scheduler descartava a partida para sempre.</p>
 */
public class GcUnavailableException extends RuntimeException {

    public GcUnavailableException(String message) {
        super(message);
    }

    public GcUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
