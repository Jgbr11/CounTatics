package com.countatic.core.service;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.dto.valve.GCMatchInfoDTO;
import com.countatic.core.entity.*;
import com.countatic.core.exception.DemoExpiredException;
import com.countatic.core.exception.GcUnavailableException;
import com.countatic.core.exception.ValveAuthException;
import com.countatic.core.exception.ValveTransientException;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Executa os {@link MatchFetchJob} pendentes, avançando cada um pela máquina
 * de estados até a notificação.
 *
 * <pre>
 *   AWAITING_SHARECODE ──(Valve publica o código)──► PENDING
 *   PENDING            ──(Game Coordinator)───────► GC_DONE
 *   GC_DONE            ──(download + parse)───────► DEMO_DONE
 *   DEMO_DONE          ──(mensagem na Steam)──────► NOTIFIED
 * </pre>
 *
 * <p>Cada passo é persistido antes do próximo. Se o processo morrer no meio de
 * um download, o job reinicia do {@code GC_DONE} — não do começo — e a consulta
 * ao GC não é refeita, porque a {@code demoUrl} já ficou guardada no job.</p>
 */
@Slf4j
@Service
public class MatchJobWorker {

    /** Jobs processados por ciclo. Evita segurar a thread por muito tempo. */
    private static final int BATCH_SIZE = 10;

    private final MatchFetchJobService jobService;
    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;
    private final ValveApiService valveApiService;
    private final DemoParserClientService demoParserClientService;
    private final MatchAnalysisService matchAnalysisService;
    private final SteamBotClientService steamBotClientService;
    private final Path demoTempDir;

    public MatchJobWorker(MatchFetchJobService jobService,
                          PlayerRepository playerRepository,
                          MatchRepository matchRepository,
                          ValveApiService valveApiService,
                          DemoParserClientService demoParserClientService,
                          MatchAnalysisService matchAnalysisService,
                          SteamBotClientService steamBotClientService,
                          @Value("${countatic.demo-temp-dir:${java.io.tmpdir}}") String demoTempDir) {
        this.jobService = jobService;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
        this.valveApiService = valveApiService;
        this.demoParserClientService = demoParserClientService;
        this.matchAnalysisService = matchAnalysisService;
        this.steamBotClientService = steamBotClientService;
        this.demoTempDir = Path.of(demoTempDir);
    }

    @Scheduled(fixedDelayString = "${countatic.worker-interval-ms:30000}", initialDelay = 20000)
    public void processPendingJobs() {
        // Encerra primeiro quem já esgotou as tentativas, para não ficarem
        // presos indefinidamente ocupando espaço na fila.
        for (MatchFetchJob exhausted : jobService.findExhausted()) {
            abandonExhausted(exhausted);
        }

        List<MatchFetchJob> jobs = jobService.findRunnable(BATCH_SIZE);
        if (jobs.isEmpty()) {
            return;
        }

        log.info("⚙️ Processando {} job(s) de partida...", jobs.size());

        for (MatchFetchJob job : jobs) {
            try {
                processJob(job);
            } catch (Exception e) {
                // Rede de segurança: nenhuma exceção inesperada pode derrubar
                // o lote inteiro nem deixar o job travado sem reagendamento.
                log.error("Erro inesperado no job #{}: {}", job.getId(), e.getMessage(), e);
                jobService.markFailed(job, "Erro inesperado: " + e.getMessage());
            }
        }
    }

    /** Executa UM passo da máquina de estados. */
    public void processJob(MatchFetchJob job) {
        switch (job.getStatus()) {
            case AWAITING_SHARECODE -> probeForShareCode(job);
            case PENDING, FAILED -> queryGameCoordinator(job);
            case GC_DONE -> analyzeDemo(job);
            case DEMO_DONE -> notifyPlayer(job);
            default -> log.debug("Job #{} em estado terminal ({}), ignorado.",
                    job.getId(), job.getStatus());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  AWAITING_SHARECODE → PENDING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sonda a Valve pelo share code de uma partida detectada via GSI.
     *
     * <p>O Game State Integration avisa que a partida acabou, mas não fornece
     * share code — ele só é publicado pela Valve alguns minutos depois.</p>
     */
    private void probeForShareCode(MatchFetchJob job) {
        Optional<Player> playerOpt = playerRepository.findBySteamId64(job.getSteamId64());
        if (playerOpt.isEmpty()) {
            jobService.markAbandoned(job, "Jogador não encontrado no sistema.");
            return;
        }

        Player player = playerOpt.get();
        if (player.getAuthCode() == null || player.getLatestShareCode() == null) {
            jobService.markAbandoned(job, "Jogador sem Auth Code ou Share Code cadastrados.");
            return;
        }

        String nextShareCode;
        try {
            nextShareCode = valveApiService.fetchNextMatchShareCode(
                    player.getSteamId64(), player.getAuthCode(), player.getLatestShareCode());
        } catch (ValveAuthException e) {
            jobService.markAbandoned(job, "Credenciais da Valve inválidas: " + e.getMessage());
            return;
        } catch (ValveTransientException e) {
            jobService.rescheduleShareCodeProbe(job);
            return;
        }

        if (nextShareCode == null || nextShareCode.equalsIgnoreCase(player.getLatestShareCode())) {
            // Normal: a Valve ainda não publicou. Não é falha.
            jobService.rescheduleShareCodeProbe(job);
            return;
        }

        // O polling pode ter descoberto a mesma partida primeiro. A constraint
        // única decide; se perdemos a corrida, esta sondagem vira duplicata.
        var created = jobService.enqueueIfAbsent(
                job.getSteamId64(), nextShareCode, FetchSource.GSI);

        if (created.isEmpty()) {
            jobService.markAbandoned(job,
                    "Partida já enfileirada pelo ciclo de descoberta (duplicata do GSI).");
        } else {
            // A sondagem cumpriu o papel; o job novo carrega o processamento.
            job.setStatus(MatchFetchStatus.ABANDONED);
            job.setLastError(null);
            jobService.save(job);
            log.info("✅ Share code {} publicado — job de sondagem #{} concluído.",
                    nextShareCode, job.getId());
        }

        player.setLatestShareCode(nextShareCode);
        playerRepository.save(player);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PENDING → GC_DONE
    // ═══════════════════════════════════════════════════════════════════

    private void queryGameCoordinator(MatchFetchJob job) {
        GCMatchInfoDTO.MatchInfo gcInfo;
        try {
            gcInfo = steamBotClientService.requestMatchInfo(job.getShareCode(), job.getSteamId64());
        } catch (GcUnavailableException e) {
            // Transitório: o bot pode estar reiniciando ou o GC lento. Retenta.
            jobService.markFailed(job, "GC indisponível: " + e.getMessage());
            return;
        }

        if (gcInfo == null) {
            // 404: a partida não existe mais no GC. Terminal — retentar não traz de volta.
            jobService.markAbandoned(job,
                    "O Game Coordinator não conhece mais esta partida "
                            + "(fora da janela de retenção da Valve).");
            notifyQuietly(job.getSteamId64(),
                    "🎮 Detectei uma partida sua, mas a Valve já não tem os dados dela.\n"
                            + "Partidas antigas saem do sistema depois de um tempo.");
            return;
        }

        job.setDemoUrl(gcInfo.getDemoUrl());
        job.setMatchTimeUnix(gcInfo.getMatchTimeUnix());
        job.setAttempts(0); // etapa vencida: zera o contador para a próxima
        job.setLastError(null);

        boolean hasDemo = gcInfo.getDemoUrl() != null && !gcInfo.getDemoUrl().isBlank();

        if (hasDemo) {
            // Segura a notificação: em instantes teremos a análise completa e
            // mandamos UMA mensagem com o link, em vez de duas seguidas
            // (que era o que provocava RateLimitExceeded na Steam).
            job.setStatus(MatchFetchStatus.GC_DONE);
            jobService.save(job);
            log.info("✅ Job #{}: stats do GC obtidas, demo disponível — seguindo para análise.",
                    job.getId());
        } else {
            // Sem demo, o relatório do GC é tudo que teremos. Envia agora.
            String report = steamBotClientService.formatGCStatsReport(job.getSteamId64(), gcInfo);
            boolean delivered = steamBotClientService.sendSimpleNotification(job.getSteamId64(), report);

            if (delivered) {
                job.setNotifiedBasicAt(Instant.now());
                job.setStatus(MatchFetchStatus.NOTIFIED);
                jobService.save(job);
                log.info("📊 Job #{}: relatório básico entregue (sem demo disponível).", job.getId());
            } else {
                jobService.markFailed(job, "Falha ao entregar o relatório básico no chat da Steam.");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GC_DONE → DEMO_DONE
    // ═══════════════════════════════════════════════════════════════════

    private void analyzeDemo(MatchFetchJob job) {
        ValveApiService.DownloadedDemo downloaded = null;

        try {
            downloaded = valveApiService.downloadAndDecompressToFile(job.getDemoUrl(), demoTempDir);

            // Se esta demo já foi analisada (retry após falha na notificação,
            // ou a mesma partida vista por dois jogadores), reaproveita.
            Optional<Match> existing = matchRepository.findByDemoFileHash(downloaded.sha256());
            if (existing.isPresent()) {
                log.info("Job #{}: demo já analisada anteriormente (match ID={}).",
                        job.getId(), existing.get().getId());
                job.setMatch(existing.get());
                job.setStatus(MatchFetchStatus.DEMO_DONE);
                job.setAttempts(0);
                jobService.save(job);
                return;
            }

            ParsedDemoDTO parsed = demoParserClientService.parseDemo(downloaded.demoFile());

            Instant playedAt = (job.getMatchTimeUnix() != null && job.getMatchTimeUnix() > 0)
                    ? Instant.ofEpochSecond(job.getMatchTimeUnix())
                    : null;

            MatchAnalysisResult result = matchAnalysisService.processDemo(
                    job.getShareCode() + ".dem", downloaded.sha256(), parsed, playedAt);

            matchRepository.findById(result.getMatchId()).ifPresent(job::setMatch);
            job.setStatus(MatchFetchStatus.DEMO_DONE);
            job.setAttempts(0);
            job.setLastError(null);
            jobService.save(job);

            log.info("🎯 Job #{}: análise concluída (match ID={}).", job.getId(), result.getMatchId());

        } catch (DemoExpiredException e) {
            jobService.markDemoExpired(job, e.getMessage());
            notifyQuietly(job.getSteamId64(),
                    "📼 A demo desta partida já saiu do servidor da Valve "
                            + "(replays ficam disponíveis por ~2 semanas), "
                            + "então não deu para fazer a análise detalhada.");

        } catch (Exception e) {
            jobService.markFailed(job, "Falha ao analisar a demo: " + e.getMessage());

        } finally {
            if (downloaded != null) {
                valveApiService.deleteQuietly(downloaded.demoFile());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DEMO_DONE → NOTIFIED
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Envia UMA mensagem curta com o link para os detalhes completos.
     *
     * <p>Substitui o esquema anterior de duas mensagens longas em sequência,
     * que estourava o rate limit de chat da Steam. Todo o detalhamento vive na
     * página web, onde cabe muito mais informação do que numa mensagem de chat.</p>
     */
    private void notifyPlayer(MatchFetchJob job) {
        if (job.getMatch() == null) {
            jobService.markAbandoned(job, "Job em DEMO_DONE sem partida associada.");
            return;
        }

        // Recarrega por id em vez de usar a instância vinda do job.
        // `getId()` funciona mesmo num proxy detached (o identificador já é
        // conhecido), e o findById devolve uma entidade gerenciada e completa —
        // imune ao "could not initialize proxy - no Session" que derrubava
        // este passo quando a associação chegava fora da sessão.
        Match match = matchRepository.findById(job.getMatch().getId()).orElse(null);
        if (match == null) {
            jobService.markAbandoned(job, "Partida associada ao job não existe mais.");
            return;
        }

        String message = steamBotClientService.formatMatchLinkMessage(job.getSteamId64(), match);
        boolean delivered = steamBotClientService.sendSimpleNotification(job.getSteamId64(), message);

        if (delivered) {
            job.setNotifiedDeepAt(Instant.now());
            job.setStatus(MatchFetchStatus.NOTIFIED);
            job.setAttempts(0);
            job.setLastError(null);
            jobService.save(job);
            log.info("✅ Job #{}: jogador {} notificado com o link da partida {}.",
                    job.getId(), job.getSteamId64(), match.getId());
        } else {
            // A análise está salva; só a entrega falhou. Não afirmar entrega
            // sem entrega — o retry cuida do resto.
            jobService.markFailed(job,
                    "Análise concluída (match ID=" + match.getId()
                            + "), mas o envio da mensagem falhou.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════

    private void abandonExhausted(MatchFetchJob job) {
        jobService.markAbandoned(job,
                "Tentativas esgotadas (" + MatchFetchJobService.MAX_ATTEMPTS + "). "
                        + "Último erro: " + job.getLastError());

        notifyQuietly(job.getSteamId64(),
                "⚠️ Não consegui analisar uma das suas partidas após várias tentativas.\n"
                        + "Share code: " + job.getShareCode());
    }

    private void notifyQuietly(String steamId64, String message) {
        try {
            steamBotClientService.sendSimpleNotification(steamId64, message);
        } catch (Exception e) {
            log.warn("Não foi possível notificar {}: {}", steamId64, e.getMessage());
        }
    }
}
