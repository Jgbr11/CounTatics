package com.countatic.core.service;

import com.countatic.core.entity.Match;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Descarta a análise de uma partida e rearma o job para que ela seja refeita
 * do zero, a partir da demo original no CDN da Valve.
 *
 * <p><b>Para que serve.</b> Mudanças no parser Go não alcançam partidas já
 * analisadas: os eventos foram persistidos sem os campos novos, e recalcular
 * sobre eles apenas reproduz o resultado antigo. Foi o que aconteceu com o
 * crosshair placement — o parser passou a anexar a cabeça do inimigo a cada
 * disparo, mas as partidas anteriores continuaram com {@code victimPosition}
 * nulo e score zerado.</p>
 *
 * <p><b>Por que só rearma.</b> Baixar e parsear já é responsabilidade do
 * {@link MatchJobWorker}, com streaming para disco, verificação de hash,
 * tratamento de demo expirada e backoff. Este serviço devolve o job ao estado
 * {@code GC_DONE} e sai do caminho; o worker refaz o resto no ciclo seguinte.</p>
 *
 * <p><b>Janela de oportunidade.</b> A Valve descarta os replays em cerca de
 * duas semanas. Passado esse prazo o download devolve 404, o worker marca
 * {@code DEMO_EXPIRED} e a partida fica com os dados que tiver.</p>
 */
@Slf4j
@Service
public class MatchReprocessService {

    private final MatchRepository matchRepository;
    private final MatchFetchJobRepository jobRepository;
    private final PlayerMatchStatsRepository statsRepository;

    public MatchReprocessService(MatchRepository matchRepository,
                                 MatchFetchJobRepository jobRepository,
                                 PlayerMatchStatsRepository statsRepository) {
        this.matchRepository = matchRepository;
        this.jobRepository = jobRepository;
        this.statsRepository = statsRepository;
    }

    /**
     * Apaga a análise existente e devolve o job a {@code GC_DONE}.
     *
     * @return id do job rearmado
     * @throws IllegalArgumentException se a partida não existe
     * @throws IllegalStateException    se não há job com URL de demo para ela
     */
    @Transactional
    public Long rearmar(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada: " + matchId));

        // Lista, não um único job: a mesma partida pode ter sido reportada por
        // mais de um jogador da sessão (MatchJobWorker.analyzeDemo reaproveita
        // a Match já analisada e aponta o job do segundo jogador para ela). O
        // mais recente é quem carrega a URL de demo mais confiável.
        List<MatchFetchJob> jobs = jobRepository.findByMatchIdOrderByIdDesc(matchId);
        if (jobs.isEmpty()) {
            throw new IllegalStateException(
                    "A partida " + matchId + " não tem job associado — sem job não há "
                            + "URL da demo. Partidas enviadas por upload manual não podem "
                            + "ser re-baixadas.");
        }

        MatchFetchJob job = jobs.get(0);

        if (job.getDemoUrl() == null || job.getDemoUrl().isBlank()) {
            throw new IllegalStateException(
                    "O job #" + job.getId() + " não guardou a URL da demo; não há de onde re-baixar.");
        }

        // O rating vive na Match quando o job é anterior à captura de CS Rating.
        // O worker o lê de job.getCsRating() ao chamar processDemo: sem esta
        // cópia, o re-parse devolveria a partida sem faixa e fora da base de
        // comparação — trocando um defeito por outro.
        if (job.getCsRating() == null && match.getCsRating() != null) {
            job.setCsRating(match.getCsRating());
        }

        // Solta a FK de TODOS os jobs que apontam para esta partida, não só do
        // que vai ser rearmado: qualquer um deles que continue com match_id
        // preenchido barra o delete da Match, porque essa FK fica fora da
        // árvore de cascade. Os jobs irmãos mantêm o próprio status — só a
        // referência à partida é solta.
        jobs.forEach(j -> j.setMatch(null));
        jobRepository.saveAllAndFlush(jobs);

        statsRepository.deleteByMatchId(matchId);
        matchRepository.delete(match);

        // Rounds e MatchEvents saem por cascade + orphanRemoval declarados em
        // Match e Round; só player_match_stats e match_fetch_jobs ficam de fora.

        job.setStatus(MatchFetchStatus.GC_DONE);
        job.setAttempts(0);
        job.setLastError(null);
        job.setNextAttemptAt(Instant.now());
        jobRepository.save(job);

        log.info("♻️ Partida {} descartada; job #{} rearmado em GC_DONE (share code {}).",
                matchId, job.getId(), job.getShareCode());

        return job.getId();
    }
}
