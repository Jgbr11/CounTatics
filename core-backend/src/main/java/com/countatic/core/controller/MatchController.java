package com.countatic.core.controller;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.service.DemoParserClientService;
import com.countatic.core.service.MatchAnalysisService;
import com.countatic.core.service.MatchFetchJobService;
import com.countatic.core.service.MatchQueryService;
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

    public MatchController(MatchQueryService matchQueryService,
                           MatchFetchJobService jobService,
                           DemoParserClientService demoParserClientService,
                           MatchAnalysisService matchAnalysisService) {
        this.matchQueryService = matchQueryService;
        this.jobService = jobService;
        this.demoParserClientService = demoParserClientService;
        this.matchAnalysisService = matchAnalysisService;
    }

    /** Lista as partidas analisadas, mais recentes primeiro. */
    @GetMapping("/matches")
    public ResponseEntity<?> listMatches(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(matchQueryService.findRecent(Math.min(limit, 100)));
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
