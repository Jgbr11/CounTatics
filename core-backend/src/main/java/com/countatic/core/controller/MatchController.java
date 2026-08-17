package com.countatic.core.controller;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.service.BaselineService;
import com.countatic.core.service.DemoParserClientService;
import com.countatic.core.service.MatchAnalysisService;
import com.countatic.core.service.MatchFetchJobService;
import com.countatic.core.service.MatchQueryService;
import com.countatic.core.service.MatchReprocessService;
import com.countatic.core.service.PlayerDashboardService;
import com.countatic.core.service.PlayerTrendService;
import com.countatic.core.repository.PlayerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * API de leitura de partidas e upload manual de demos.
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class MatchController {

    private final MatchQueryService matchQueryService;
    private final MatchFetchJobService jobService;
    private final DemoParserClientService demoParserClientService;
    private final MatchAnalysisService matchAnalysisService;
    private final MatchReprocessService matchReprocessService;
    private final PlayerTrendService trendService;
    private final PlayerDashboardService dashboardService;
    private final PlayerRepository playerRepository;

    public MatchController(MatchQueryService matchQueryService,
                           MatchFetchJobService jobService,
                           DemoParserClientService demoParserClientService,
                           MatchAnalysisService matchAnalysisService,
                           MatchReprocessService matchReprocessService,
                           PlayerTrendService trendService,
                           PlayerDashboardService dashboardService,
                           PlayerRepository playerRepository) {
        this.matchQueryService = matchQueryService;
        this.jobService = jobService;
        this.demoParserClientService = demoParserClientService;
        this.matchAnalysisService = matchAnalysisService;
        this.matchReprocessService = matchReprocessService;
        this.trendService = trendService;
        this.dashboardService = dashboardService;
        this.playerRepository = playerRepository;
    }

    /** Lista as partidas analisadas, mais recentes primeiro. */
    @GetMapping("/matches")
    public ResponseEntity<?> listMatches(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(matchQueryService.findRecent(Math.min(limit, 100)));
    }

    /**
     * Recalcula os desempenhos de uma partida já analisada.
     *
     * <p>Usos: alimentar a base de comparação com partidas analisadas antes de
     * existir a captura de CS Rating, e reprocessar o histórico quando uma
     * fórmula muda. Funciona a partir dos eventos persistidos — não depende da
     * demo original, que expira no CDN da Valve.</p>
     *
     * @param csRating rating a atribuir à partida; omitido preserva o atual
     */
    @PostMapping("/matches/{id}/recompute")
    public ResponseEntity<?> recompute(@PathVariable("id") Long id,
                                       @RequestParam(required = false) Integer csRating) {
        try {
            int jogadores = matchAnalysisService.recomputePlayerStats(id, csRating);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "matchId", id,
                    "playersRecomputed", jogadores
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Falha ao recomputar a partida {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Descarta a análise de uma partida e a refaz a partir da demo no CDN.
     *
     * <p>Diferente de {@code /recompute}, que recalcula sobre os eventos já
     * gravados, este endpoint re-baixa e re-parseia — é o único caminho quando o
     * parser passou a extrair um campo que as partidas antigas não têm.</p>
     *
     * <p>Responde <b>202 Accepted</b>: quem executa é o worker, no ciclo de 30 s.
     * Download e parsing levam poucos minutos; acompanhe por
     * {@code GET /api/players/{steamId}/jobs}.</p>
     *
     * <p><b>A partida recebe um publicToken novo</b>, então o link {@code /m/...}
     * já enviado no chat deixa de responder. O worker manda um link novo ao
     * concluir. Rearme uma partida por vez para não estourar o rate limit do
     * chat da Steam.</p>
     */
    @PostMapping("/matches/{id}/reparse")
    public ResponseEntity<?> reparse(@PathVariable("id") Long id) {
        try {
            Long jobId = matchReprocessService.rearmar(id);
            return ResponseEntity.accepted().body(Map.of(
                    "success", true,
                    "jobId", jobId,
                    "message", "Partida descartada. O worker vai re-baixar e re-parsear "
                            + "em até 30 segundos."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Falha ao rearmar a partida {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** Detalhes completos de uma partida, com métricas por jogador. */
    @GetMapping("/matches/{id}")
    public ResponseEntity<?> getMatch(@PathVariable("id") Long id) {
        return matchQueryService.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Histórico de jobs de um jogador.
     *
     * <p>É a superfície de diagnóstico do pipeline: mostra em que etapa cada
     * partida está, quantas tentativas já houve, qual foi o último erro e
     * quando será a próxima tentativa.</p>
     */
    @GetMapping("/players/{steamId}/jobs")
    public ResponseEntity<?> listJobs(@PathVariable("steamId") String steamId) {
        List<MatchFetchJob> jobs = jobService.findByPlayer(steamId);

        // Projeção manual: a entidade tem referência LAZY a Match, que
        // serializaria a árvore inteira (ou explodiria fora da transação).
        List<Map<String, Object>> body = jobs.stream()
                .map(j -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", j.getId());
                    m.put("shareCode", j.getShareCode());
                    m.put("status", j.getStatus());
                    m.put("source", j.getSource());
                    m.put("attempts", j.getAttempts());
                    m.put("lastError", j.getLastError());
                    m.put("nextAttemptAt", j.getNextAttemptAt());
                    m.put("notifiedBasicAt", j.getNotifiedBasicAt());
                    m.put("notifiedDeepAt", j.getNotifiedDeepAt());
                    m.put("createdAt", j.getCreatedAt());
                    m.put("updatedAt", j.getUpdatedAt());
                    return m;
                })
                .toList();

        return ResponseEntity.ok(body);
    }

    /**
     * Evolução de uma métrica do jogador ao longo das últimas partidas.
     *
     * <p>Fica aqui, ao lado de {@code /jobs}, porque é a outra leitura por
     * jogador — o {@code PlayerAuthController} cuida só de credenciais.</p>
     *
     * <p>Métrica inválida devolve <b>400 com a lista das válidas</b>, e não um
     * 404 ou uma série vazia: série vazia é indistinguível de "jogador sem
     * histórico" e mandaria quem integra procurar o problema no lugar errado.</p>
     */
    @GetMapping("/players/{steamId}/trend")
    public ResponseEntity<?> trend(@PathVariable("steamId") String steamId,
                                   @RequestParam(name = "metric", defaultValue = "adr") String metric,
                                   @RequestParam(name = "limit",
                                           defaultValue = "" + PlayerTrendService.LIMITE_PADRAO) int limit) {
        try {
            return ResponseEntity.ok(trendService.serie(steamId, metric, limit));
        } catch (PlayerTrendService.MetricaDesconhecidaException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "metricasValidas", BaselineService.metricasSuportadas()
            ));
        }
    }

    /**
     * Várias séries de uma vez, para as sparklines dos cards.
     *
     * <p>Uma requisição por card significaria dez consultas idênticas variando
     * só a coluna lida — as linhas necessárias são as mesmas. Aqui o banco é
     * lido uma vez.</p>
     */
    @GetMapping("/players/{steamId}/trends")
    public ResponseEntity<?> trends(@PathVariable("steamId") String steamId,
                                    @RequestParam(name = "metrics") List<String> metrics,
                                    @RequestParam(name = "limit",
                                            defaultValue = "" + PlayerTrendService.LIMITE_PADRAO) int limit) {
        try {
            return ResponseEntity.ok(Map.of("series", trendService.series(steamId, metrics, limit)));
        } catch (PlayerTrendService.MetricaDesconhecidaException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "metricasValidas", BaselineService.metricasSuportadas()
            ));
        }
    }

    /**
     * Painel consolidado do jogador, em JSON.
     *
     * <p>Aceita SteamID64 porque é a API — quem chama já conhece o jogador. A
     * <b>página</b> equivalente ({@code /p/{token}}) usa o token público, que é
     * o que impede alguém de ver o histórico de outro sabendo só o SteamID.</p>
     *
     * <p>A resposta inclui {@code painelUrl}: sem ela não haveria como
     * descobrir o token, que é aleatório por construção.</p>
     */
    @GetMapping("/players/{steamId}/painel")
    public ResponseEntity<?> painel(@PathVariable("steamId") String steamId,
                                    @RequestParam(name = "partidas",
                                            defaultValue = "" + PlayerDashboardService.PARTIDAS_PADRAO)
                                    int partidas) {
        return playerRepository.findBySteamId64(steamId)
                .map(jogador -> dashboardService.porSteamId(steamId, partidas)
                        .<ResponseEntity<?>>map(painel -> ResponseEntity.ok(Map.of(
                                "painelUrl", "/p/" + jogador.getPublicToken(),
                                "painel", painel)))
                        .orElseGet(() -> ResponseEntity.notFound().build()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Envia um arquivo {@code .dem} manualmente para análise.
     *
     * <p>Vale por si mesmo: permite validar mudanças no parser em segundos, sem
     * esperar uma partida nova nem depender do CDN da Valve — que, aliás,
     * descarta os replays após cerca de duas semanas, tornando este o único
     * caminho para analisar partidas antigas que você tenha salvo localmente.</p>
     */
    @PostMapping("/matches/upload")
    public ResponseEntity<?> uploadDemo(@RequestParam("demo") MultipartFile demo) {
        if (demo.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "Arquivo vazio."));
        }

        String fileName = demo.getOriginalFilename() == null ? "upload.dem" : demo.getOriginalFilename();
        log.info("📤 Upload manual recebido: {} ({} MB)",
                fileName, String.format("%.2f", demo.getSize() / (1024.0 * 1024.0)));

        try {
            byte[] bytes = demo.getBytes();
            String hash = sha256(bytes);

            ParsedDemoDTO parsed = demoParserClientService.parseDemo(fileName, bytes);
            MatchAnalysisResult result = matchAnalysisService.processDemo(fileName, hash, parsed);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "matchId", result.getMatchId(),
                    "mapName", result.getMapName(),
                    "finalScore", result.getFinalScore()
            ));
        } catch (IllegalStateException e) {
            // Hash duplicado: a demo já foi analisada. Não é erro do usuário.
            return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Falha ao processar upload de {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }
}
