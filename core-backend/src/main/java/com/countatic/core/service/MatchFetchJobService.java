package com.countatic.core.service;

import com.countatic.core.entity.FetchSource;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.repository.MatchFetchJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ciclo de vida dos {@link MatchFetchJob}: enfileiramento idempotente,
 * transições de estado e política de retry.
 */
@Slf4j
@Service
public class MatchFetchJobService {

    /** Tentativas antes de abandonar o job. */
    public static final int MAX_ATTEMPTS = 6;

    /**
     * Backoff por número de tentativas já feitas.
     *
     * <p>Cresce rápido de propósito: as falhas mais comuns aqui (GC fora do ar,
     * CDN instável, bot reiniciando) ou se resolvem em minutos, ou levam horas.
     * Retentar de 30 em 30 segundos só gera log e pressão na Steam.</p>
     */
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1),
            Duration.ofMinutes(3),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(6)
    };

    /**
     * Backoff da sondagem por share code após o GSI avisar o fim da partida.
     *
     * <p>Bem mais curto que o backoff de falha: aqui nada falhou — só estamos
     * esperando a Valve publicar o código, o que costuma levar poucos minutos.
     * Soma ~80 min no total antes de desistir.</p>
     */
    private static final Duration[] SHARECODE_BACKOFF = {
            Duration.ofSeconds(45),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(10),
            Duration.ofMinutes(20),
            Duration.ofMinutes(40)
    };

    /** Estados que o worker pode processar. */
    public static final List<MatchFetchStatus> RUNNABLE_STATUSES = List.of(
            MatchFetchStatus.AWAITING_SHARECODE,
            MatchFetchStatus.PENDING,
            MatchFetchStatus.GC_DONE,
            MatchFetchStatus.DEMO_DONE,
            MatchFetchStatus.FAILED
    );

    private final MatchFetchJobRepository jobRepository;

    public MatchFetchJobService(MatchFetchJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Cria um job para o share code se ainda não existir.
     *
     * <p>Roda em transação <b>própria</b> ({@code REQUIRES_NEW}) para que o
     * commit aconteça imediatamente: o scheduler de descoberta só avança o
     * ponteiro do jogador depois que este registro está durável. Se a
     * transação externa fosse compartilhada, um rollback posterior levaria o
     * job junto e a partida se perderia — exatamente o problema que esta
     * tabela existe para resolver.</p>
     *
     * <p>A checagem prévia é otimização, não garantia. O que realmente impede
     * duplicata é a constraint única {@code (steamId64, shareCode)}: entre o
     * SELECT e o INSERT, a sondagem do GSI e o ciclo do polling podem correr
     * em paralelo. Por isso a violação é capturada e tratada como no-op.</p>
     *
     * @return o job criado, ou {@link Optional#empty()} se já existia
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MatchFetchJob> enqueueIfAbsent(String steamId64, String shareCode, FetchSource source) {
        Optional<MatchFetchJob> existing = jobRepository.findBySteamId64AndShareCode(steamId64, shareCode);
        if (existing.isPresent()) {
            log.debug("Job já existe para {} / {} (id={})", steamId64, shareCode, existing.get().getId());
            return Optional.empty();
        }

        MatchFetchJob job = MatchFetchJob.builder()
                .steamId64(steamId64)
                .shareCode(shareCode)
                .status(MatchFetchStatus.PENDING)
                .source(source)
                .attempts(0)
                .nextAttemptAt(Instant.now())
                .build();

        try {
            MatchFetchJob saved = jobRepository.saveAndFlush(job);
            log.info("📋 Job #{} criado para {} ({} via {})",
                    saved.getId(), shareCode, steamId64, source);
            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // Corrida perdida: a outra origem inseriu primeiro. É o comportamento
            // esperado, não um erro — a constraint única fez o seu trabalho.
            log.debug("Job para {} / {} criado concorrentemente por outra origem.", steamId64, shareCode);
            return Optional.empty();
        }
    }

    /**
     * Cria um job de sondagem para uma partida que o GSI detectou mas cujo
     * share code a Valve ainda não publicou.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MatchFetchJob enqueueAwaitingShareCode(String steamId64, String mapName,
                                                  Integer scoreSelf, Integer scoreEnemy,
                                                  String statsJson) {
        MatchFetchJob job = MatchFetchJob.builder()
                .steamId64(steamId64)
                .shareCode(null)
                .status(MatchFetchStatus.AWAITING_SHARECODE)
                .source(FetchSource.GSI)
                .attempts(0)
                .nextAttemptAt(Instant.now().plus(SHARECODE_BACKOFF[0]))
                .gsiMapName(mapName)
                .gsiScoreSelf(scoreSelf)
                .gsiScoreEnemy(scoreEnemy)
                .gsiStatsJson(statsJson)
                .build();

        MatchFetchJob saved = jobRepository.saveAndFlush(job);
        log.info("📋 Job #{} (GSI) criado para {} — aguardando share code da Valve.",
                saved.getId(), steamId64);
        return saved;
    }

    /** Jobs prontos para uma nova tentativa, limitados por lote. */
    @Transactional(readOnly = true)
    public List<MatchFetchJob> findRunnable(int limit) {
        return jobRepository.findRunnable(
                RUNNABLE_STATUSES, Instant.now(), MAX_ATTEMPTS, PageRequest.of(0, limit));
    }

    /** Jobs que já esgotaram as tentativas e precisam ser encerrados. */
    @Transactional(readOnly = true)
    public List<MatchFetchJob> findExhausted() {
        return jobRepository.findExhausted(RUNNABLE_STATUSES, MAX_ATTEMPTS);
    }

    @Transactional
    public void updateStatus(MatchFetchJob job, MatchFetchStatus status) {
        job.setStatus(status);
        job.setLastError(null);
        jobRepository.save(job);
    }

    /**
     * Marca falha transitória e reagenda com backoff.
     */
    @Transactional
    public void markFailed(MatchFetchJob job, String error) {
        int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
        job.setAttempts(attempts + 1);
        job.setStatus(MatchFetchStatus.FAILED);
        job.setLastError(truncate(error));

        Duration delay = BACKOFF[Math.min(attempts, BACKOFF.length - 1)];
        job.setNextAttemptAt(Instant.now().plus(delay));

        jobRepository.save(job);

        log.warn("⏳ Job #{} ({}) falhou [tentativa {}/{}]: {} — nova tentativa em {}",
                job.getId(), job.getShareCode(), job.getAttempts(), MAX_ATTEMPTS,
                error, humanize(delay));
    }

    /**
     * Reagenda a sondagem por share code (o GSI já avisou, a Valve ainda não publicou).
     * Não é falha — nada quebrou, só ainda não está disponível.
     */
    @Transactional
    public void rescheduleShareCodeProbe(MatchFetchJob job) {
        int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
        job.setAttempts(attempts + 1);

        if (job.getAttempts() >= SHARECODE_BACKOFF.length) {
            markAbandoned(job, "A Valve não publicou o share code desta partida a tempo.");
            return;
        }

        Duration delay = SHARECODE_BACKOFF[Math.min(attempts, SHARECODE_BACKOFF.length - 1)];
        job.setNextAttemptAt(Instant.now().plus(delay));
        jobRepository.save(job);

        log.debug("Job #{} — share code ainda indisponível, nova sondagem em {}",
                job.getId(), humanize(delay));
    }

    /** Encerra o job em definitivo (sem mais retry). */
    @Transactional
    public void markAbandoned(MatchFetchJob job, String reason) {
        job.setStatus(MatchFetchStatus.ABANDONED);
        job.setLastError(truncate(reason));
        job.setNextAttemptAt(null);
        jobRepository.save(job);
        log.warn("🛑 Job #{} ({}) abandonado: {}", job.getId(), job.getShareCode(), reason);
    }

    /** A demo saiu do CDN da Valve — terminal e distinto de falha. */
    @Transactional
    public void markDemoExpired(MatchFetchJob job, String reason) {
        job.setStatus(MatchFetchStatus.DEMO_EXPIRED);
        job.setLastError(truncate(reason));
        job.setNextAttemptAt(null);
        jobRepository.save(job);
        log.info("📼 Job #{} ({}): demo fora da janela de retenção da Valve.",
                job.getId(), job.getShareCode());
    }

    @Transactional
    public void save(MatchFetchJob job) {
        jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public List<MatchFetchJob> findByPlayer(String steamId64) {
        return jobRepository.findBySteamId64OrderByCreatedAtDesc(steamId64);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 1024 ? s : s.substring(0, 1021) + "...";
    }

    private static String humanize(Duration d) {
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "min";
        return d.toSeconds() + "s";
    }
}
