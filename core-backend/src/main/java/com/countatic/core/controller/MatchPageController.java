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
                    .contentType(MediaType.TEXT_HTML)
                    .body(notFoundPage());
        }

        try {
            String json = objectMapper.writeValueAsString(detail.get());
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(MatchPageTemplate.render(json));
        } catch (Exception e) {
            log.error("Falha ao renderizar a página da partida {}: {}", token, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_HTML)
                    .body(notFoundPage());
        }
    }

    private String notFoundPage() {
        return """
                <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Partida não encontrada — CounTatic</title>
                <style>
                  body{margin:0;min-height:100vh;display:grid;place-items:center;
                       background:#0d1117;color:#c9d1d9;
                       font:16px/1.6 system-ui,-apple-system,Segoe UI,sans-serif}
                  .box{text-align:center;padding:2rem}
                  h1{font-size:1.5rem;margin:0 0 .5rem}
                  p{color:#8b949e;margin:0}
                </style></head><body>
                <div class="box">
                  <h1>Partida não encontrada</h1>
                  <p>O link pode ter expirado ou estar incorreto.</p>
                </div></body></html>
                """;
    }
}
