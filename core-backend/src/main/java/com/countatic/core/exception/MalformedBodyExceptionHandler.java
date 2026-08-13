package com.countatic.core.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Trata corpo de requisição ilegível — JSON sintaticamente inválido, corpo
 * vazio, charset quebrado.
 *
 * <p><b>Por que existe.</b> O {@code GsiController} tem o contrato de nunca
 * devolver erro ao CS2: o jogo não sabe reagir a falha e, ao ver o endpoint
 * respondendo mal, para de enviar payloads. Só que esse contrato tinha um
 * buraco: um corpo JSON inválido nunca chega ao controller. O Spring estoura
 * {@link HttpMessageNotReadableException} ainda na desserialização do
 * {@code @RequestBody} e devolve 400 antes de qualquer código nosso rodar.</p>
 *
 * <p><b>Por que o 400 sobrevive nos demais endpoints.</b> Mascarar corpo
 * inválido na API inteira seria trocar um bug por outro: para
 * {@code /api/players/**} e {@code /api/matches/**}, quem chama é código nosso
 * ou um humano, e o 400 é a resposta correta e útil. O tratamento especial vale
 * exclusivamente para o path do GSI — daí a decisão ser tomada a partir do
 * {@link HttpServletRequest}, e não do tipo da exceção.</p>
 *
 * <p>Engolir em silêncio seria pior que o 400: se o CS2 passar a mandar um
 * formato que não sabemos ler, o sintoma vira "o gatilho simplesmente não
 * dispara". Por isso a rejeição sempre deixa um {@code log.warn} com o path e a
 * causa.</p>
 */
@Slf4j
@RestControllerAdvice
public class MalformedBodyExceptionHandler {

    private static final String PATH_GSI = "/api/gsi";

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> corpoIlegivel(HttpMessageNotReadableException e,
                                              HttpServletRequest request) {
        String path = pathSemContexto(request);

        if (!PATH_GSI.equals(path)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 200 mesmo com corpo quebrado: devolver 400 ao CS2 o faria abandonar o
        // endpoint, e aí nenhuma partida seguinte seria detectada — o preço é
        // desproporcional ao de descartar um payload malformado.
        log.warn("Corpo ilegível em {} — payload descartado, respondendo 200 para não "
                + "derrubar o gatilho do CS2. Causa: {}", path, e.getMostSpecificCause().getMessage());
        return ResponseEntity.ok().build();
    }

    /**
     * O {@code requestURI} inclui o context path; o mapeamento do controller,
     * não. Comparar sem descontá-lo quebraria o dia em que a aplicação for
     * publicada sob um prefixo.
     */
    private String pathSemContexto(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contexto = request.getContextPath();
        if (contexto != null && !contexto.isEmpty() && uri.startsWith(contexto)) {
            return uri.substring(contexto.length());
        }
        return uri;
    }
}
