package com.countatic.core.exception;

/**
 * A demo não está mais disponível no CDN da Valve.
 *
 * <p>Falha <b>terminal</b>: replays de CS2 ficam hospedados por cerca de duas
 * semanas. Passado esse prazo o CDN devolve 404 e nenhuma quantidade de
 * retentativas vai trazer o arquivo de volta.</p>
 */
public class DemoExpiredException extends RuntimeException {

    public DemoExpiredException(String message) {
        super(message);
    }
}
