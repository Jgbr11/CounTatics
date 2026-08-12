package com.countatic.core.controller;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.service.GsiEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Recebe o Game State Integration do CS2.
 *
 * <p>Este endpoint fica exposto na porta 8080 do host — é a única forma de o
 * jogo alcançá-lo. O token compartilhado é o que separa "o meu CS2" de
 * "qualquer processo na máquina": sem ele, enfileirar jobs falsos custaria uma
 * requisição HTTP.</p>
 *
 * <p>Responde imediatamente. O CS2 abandona a requisição em 5 s e passa a
 * considerar o endpoint morto; o trabalho pesado é do worker.</p>
 */
@Slf4j
@RestController
public class GsiController {

    private final GsiEventService gsiEventService;
    private final String tokenEsperado;

    public GsiController(GsiEventService gsiEventService,
                         @Value("${countatic.gsi.token:}") String tokenEsperado) {
        this.gsiEventService = gsiEventService;
        this.tokenEsperado = tokenEsperado == null ? "" : tokenEsperado;

        if (this.tokenEsperado.isBlank()) {
            log.warn("⚠️ countatic.gsi.token não configurado — POST /api/gsi vai recusar tudo. "
                    + "Defina GSI_TOKEN no .env e use o mesmo valor no arquivo "
                    + "gamestate_integration_countatic.cfg.");
        }
    }

    @PostMapping("/api/gsi")
    public ResponseEntity<Void> receber(@RequestBody GsiPayloadDTO payload) {
        if (!tokenConfere(payload)) {
            return ResponseEntity.status(403).build();
        }

        try {
            gsiEventService.processar(payload);
        } catch (Exception e) {
            // 200 mesmo em falha: o CS2 não sabe reagir a erro, e devolver 500
            // só o faria desistir do endpoint. O erro fica no log, onde é útil.
            log.error("Falha ao processar payload do GSI: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Comparação em tempo constante.
     *
     * <p>{@code equals} de String encerra no primeiro byte divergente, o que
     * transforma o tempo de resposta em oráculo para descobrir o token byte a
     * byte. {@code MessageDigest.isEqual} percorre o comprimento todo.</p>
     */
    private boolean tokenConfere(GsiPayloadDTO payload) {
        if (tokenEsperado.isBlank()) {
            return false;
        }
        if (payload == null || payload.getAuth() == null || payload.getAuth().getToken() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                tokenEsperado.getBytes(StandardCharsets.UTF_8),
                payload.getAuth().getToken().getBytes(StandardCharsets.UTF_8));
    }
}
