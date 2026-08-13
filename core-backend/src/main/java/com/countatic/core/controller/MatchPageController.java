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
                    .body(statusPage("404",
                            "Partida não encontrada",
                            "O link pode ter expirado ou estar incorreto."));
        }

        try {
            String json = objectMapper.writeValueAsString(detail.get());
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(MatchPageTemplate.render(json));
        } catch (Exception e) {
            log.error("Falha ao renderizar a página da partida {}: {}", token, e.getMessage(), e);
            // A partida existe; quem falhou foi a renderização. Dizer "o link
            // expirou" aqui mandaria o jogador procurar um problema que não é o
            // dele — e esconderia uma falha nossa que precisa aparecer no log.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(statusPage("500",
                            "Falha ao montar o relatório",
                            "A partida existe, mas houve um erro ao gerar esta página. "
                                    + "Tente novamente em instantes."));
        }
    }

    /**
     * Tela de estado — mesma identidade visual do relatório.
     *
     * <p>O código grande em Orbitron é o elemento de destaque; a explicação fica
     * em texto corrente, porque neon em bloco de texto cansa a leitura.</p>
     */
    private String statusPage(String code, String title, String message) {
        return ("""
                <!doctype html>
                <html lang="pt-BR">
                <head>
                __META__
                  <title>__TITLE__ — CounTatic</title>
                __FONTS__
                  <style>
                __THEME_CSS__
                    .wrap{min-height:100vh;display:grid;place-items:center;text-align:center}
                    .brand{font-family:var(--f-mono);font-size:.72rem;letter-spacing:.22em;
                           text-transform:uppercase;color:var(--neon);margin-bottom:1.5rem}
                    .code{font-family:var(--f-display);font-weight:900;
                          font-size:clamp(4rem,16vw,7rem);line-height:1;
                          letter-spacing:.06em;color:var(--neon);
                          text-shadow:0 0 28px rgba(176,38,255,.55)}
                    .msg{color:var(--muted);margin:1rem auto 0;max-width:34ch}
                    hr{border:0;height:1px;background:var(--line);margin:1.4rem auto;width:120px}
                  </style>
                </head>
                <body>
                  <main class="wrap">
                    <div>
                      <div class="brand">CounTatic</div>
                      <div class="code">__CODE__</div>
                      <hr>
                      <h1>__TITLE__</h1>
                      <p class="msg">__MESSAGE__</p>
                    </div>
                  </main>
                </body>
                </html>
                """)
                .replace("__META__", HudTheme.META)
                .replace("__FONTS__", HudTheme.FONTS)
                .replace("__THEME_CSS__", HudTheme.CSS)
                .replace("__CODE__", escape(code))
                .replace("__TITLE__", escape(title))
                .replace("__MESSAGE__", escape(message));
    }

    /**
     * Escapa o que vai para dentro do HTML.
     *
     * <p>Hoje as três strings são literais nossas, então nada aqui é
     * alcançável por entrada externa. É defesa contra o futuro: no dia em que
     * alguém passar o token da URL para a mensagem de erro, o escape já está no
     * caminho.</p>
     */
    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
