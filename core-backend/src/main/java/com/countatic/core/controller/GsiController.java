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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * A cada quantas rejeições um novo warn sai do silêncio.
     *
     * <p>Escolhi contador em vez de cooldown por tempo porque o volume aqui é
     * conhecido e constante: o CS2 manda ~2 payloads por segundo, então 100
     * rejeições ≈ 50 s. O contador dá a mesma cadência que um cooldown de
     * ~1 min, sem depender de relógio, sem estado a resetar e sem corrida entre
     * threads na leitura do instante — um {@code incrementAndGet} resolve. E
     * ele carrega de graça o total acumulado, que é o dado que o operador quer
     * ver ("está rejeitando desde sempre" vs. "começou agora").</p>
     */
    private static final long INTERVALO_LOG_REJEICAO = 100;

    private final GsiEventService gsiEventService;
    private final String tokenEsperado;

    /** Rejeições por token divergente desde o start. Ver {@link #INTERVALO_LOG_REJEICAO}. */
    private final AtomicLong rejeicoes = new AtomicLong();

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
            registrarRejeicao();
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
     * Deixa rastro da rejeição sem inundar o log e sem vazar o segredo.
     *
     * <p><b>Por que logar.</b> O modo de falha mais provável da instalação real
     * é o token do {@code gamestate_integration_countatic.cfg} divergir do
     * {@code GSI_TOKEN} do {@code .env}. O sintoma é um gatilho que nunca
     * dispara — indistinguível, do lado de fora, de "o CS2 não está mandando
     * nada". Sem uma linha de log, não há por onde começar o diagnóstico.</p>
     *
     * <p><b>Por que só o fato.</b> Nada do token entra na mensagem: nem valor,
     * nem prefixo, nem sufixo, nem comprimento. Qualquer um desses transforma o
     * log — que é lido, copiado e colado em issue — num vazamento do segredo, e
     * o comprimento sozinho já estreita um ataque de força bruta.</p>
     */
    private void registrarRejeicao() {
        long total = rejeicoes.incrementAndGet();
        if (total == 1 || total % INTERVALO_LOG_REJEICAO == 0) {
            log.warn("🔒 POST /api/gsi rejeitado por token divergente ({} rejeições até agora). "
                    + "Verifique se o token do gamestate_integration_countatic.cfg é igual ao "
                    + "GSI_TOKEN do .env.", total);
        }
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
