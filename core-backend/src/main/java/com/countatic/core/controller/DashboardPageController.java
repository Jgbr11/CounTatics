package com.countatic.core.controller;

import com.countatic.core.dto.stats.PlayerDashboardDTO;
import com.countatic.core.service.PlayerDashboardService;
import com.countatic.core.service.SessaoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Serve o painel do jogador — a visão consolidada das últimas partidas.
 *
 * <p>A URL usa o <b>token público</b> do jogador, e não o SteamID64. Não é
 * detalhe: o SteamID64 aparece no perfil da Steam de qualquer um, então usá-lo
 * na rota entregaria o histórico completo de um jogador a quem apenas soubesse
 * quem ele é. É a mesma razão pela qual a página da partida usa
 * {@code Match.publicToken} em vez do id sequencial.</p>
 */
@Slf4j
@Controller
public class DashboardPageController {

    private final PlayerDashboardService dashboardService;
    private final ObjectMapper objectMapper;
    private final SessaoService sessao;

    public DashboardPageController(PlayerDashboardService dashboardService,
                                   ObjectMapper objectMapper,
                                   SessaoService sessao) {
        this.dashboardService = dashboardService;
        this.objectMapper = objectMapper;
        this.sessao = sessao;
    }

    @GetMapping(value = "/p/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> painel(
            @PathVariable("token") String token,
            @RequestParam(name = "partidas",
                    defaultValue = "" + PlayerDashboardService.PARTIDAS_PADRAO) int partidas,
            HttpServletRequest pedido) {

        Optional<PlayerDashboardDTO> painel = dashboardService.porToken(token, partidas);

        if (painel.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(StatusPage.render("404", "Painel não encontrado",
                            "O link pode estar incorreto ou o jogador não está mais cadastrado."));
        }

        try {
            String json = objectMapper.writeValueAsString(painel.get());
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(DashboardPageTemplate.render(json, nav(token, "perfil", logado(pedido))));
        } catch (Exception e) {
            log.error("Falha ao renderizar o painel {}: {}", token, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(StatusPage.render("500", "Falha ao montar o painel",
                            "O jogador existe, mas houve um erro ao gerar esta página. "
                                    + "Tente novamente em instantes."));
        }
    }

    /**
     * Lista das partidas do jogador, com filtro por mapa.
     *
     * <p>Usa o mesmo serviço do painel, com a janela cheia: a página existe
     * justamente para ver além das últimas vinte.</p>
     */
    @GetMapping(value = "/p/{token}/partidas", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> partidas(@PathVariable("token") String token,
                                           HttpServletRequest pedido) {
        Optional<PlayerDashboardDTO> painel =
                dashboardService.porToken(token, PlayerDashboardService.PARTIDAS_MAXIMO);

        if (painel.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(StatusPage.render("404", "Painel não encontrado",
                            "O link pode estar incorreto ou o jogador não está mais cadastrado."));
        }

        try {
            String json = objectMapper.writeValueAsString(painel.get());
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(MatchListPageTemplate.render(json, nav(token, "partidas", logado(pedido))));
        } catch (Exception e) {
            log.error("Falha ao renderizar a lista de partidas {}: {}", token, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(StatusPage.render("500", "Falha ao montar a lista",
                            "Houve um erro ao gerar esta página. Tente novamente em instantes."));
        }
    }

    /** Barra de navegação das telas do jogador. */
    static String nav(String token, String ativa, boolean logado) {
        String perfil = "/p/" + token;
        return PageNav.render(perfil, PageNav.comSessao(logado, java.util.List.of(
                PageNav.item(perfil, "Perfil", "perfil".equals(ativa)),
                PageNav.item(perfil + "/partidas", "Partidas", "partidas".equals(ativa))
        )));
    }

    private boolean logado(HttpServletRequest pedido) {
        return sessao.steamIdLogado(pedido).isPresent();
    }
}
