package com.countatic.core.controller;

import com.countatic.core.dto.stats.MatchDetailDTO;
import com.countatic.core.service.MatchQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Serve a página de detalhes da partida — o destino do link enviado no chat
 * da Steam.
 *
 * <p>A página é montada como um único HTML autocontido, com os dados embutidos
 * como JSON. Isso evita CORS, uma segunda requisição e qualquer build de
 * frontend, mantendo o deploy em um só container.</p>
 */
@Slf4j
@Controller
public class MatchPageController {

    private final MatchQueryService matchQueryService;
    private final ObjectMapper objectMapper;

    public MatchPageController(MatchQueryService matchQueryService, ObjectMapper objectMapper) {
        this.matchQueryService = matchQueryService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/m/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> matchPage(@PathVariable("token") String token) {
        Optional<MatchDetailDTO> detail = matchQueryService.findByPublicToken(token);

        if (detail.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(StatusPage.render("404",
                            "Partida não encontrada",
                            "O link pode ter expirado ou estar incorreto."));
        }

        try {
            String json = objectMapper.writeValueAsString(detail.get());
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(MatchPageTemplate.render(json, nav(detail.get().getOwnerToken(), token)));
        } catch (Exception e) {
            log.error("Falha ao renderizar a página da partida {}: {}", token, e.getMessage(), e);
            // A partida existe; quem falhou foi a renderização. Dizer "o link
            // expirou" aqui mandaria o jogador procurar um problema que não é o
            // dele — e esconderia uma falha nossa que precisa aparecer no log.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(StatusPage.render("500",
                            "Falha ao montar o relatório",
                            "A partida existe, mas houve um erro ao gerar esta página. "
                                    + "Tente novamente em instantes."));
        }
    }


    /**
     * Navegação da página da partida.
     *
     * <p>Sem um participante cadastrado não há perfil para onde voltar, e a
     * barra fica só com a marca — melhor que um link quebrado. Acontece de
     * verdade: dá para abrir o relatório de uma partida em que ninguém do
     * lobby usa o sistema.</p>
     */
    private static String nav(String ownerToken, String matchToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            return PageNav.render(null);
        }
        String perfil = "/p/" + ownerToken;
        return PageNav.render(perfil, java.util.List.of(
                PageNav.item(perfil, "Perfil", false),
                PageNav.item(perfil + "/partidas", "Partidas", false),
                // A própria página precisa aparecer na barra: sem ela, quem
                // chega aqui vê dois destinos e nenhum indício de onde está.
                PageNav.item("/m/" + matchToken, "Partida", true)
        ));
    }
}
