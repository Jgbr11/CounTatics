package com.countatic.core.service;

import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class MatchReprocessServiceTest {

    private MatchRepository matchRepository;
    private MatchFetchJobRepository jobRepository;
    private PlayerMatchStatsRepository statsRepository;
    private MatchReprocessService service;

    private Match match;
    private MatchFetchJob job;

    @BeforeEach
    void setUp() {
        matchRepository = mock(MatchRepository.class);
        jobRepository = mock(MatchFetchJobRepository.class);
        statsRepository = mock(PlayerMatchStatsRepository.class);
        service = new MatchReprocessService(matchRepository, jobRepository, statsRepository);

        match = Match.builder()
                .id(1L)
                .mapName("de_inferno")
                .csRating(10096)
                .rankTier(RankTier.AZUL)
                .build();

        job = MatchFetchJob.builder()
                .id(7L)
                .steamId64("76561199110265389")
                .shareCode("CSGO-Pf6Xz-AXpEG-GdzKr-GyWv6-pdueM")
                .status(MatchFetchStatus.NOTIFIED)
                .demoUrl("http://replay201.valve.net/730/003835751950364704778_0322641992.dem.bz2")
                .csRating(null)
                .match(match)
                .attempts(3)
                .lastError("erro antigo")
                .build();

        when(matchRepository.findById(1L)).thenReturn(Optional.of(match));
        when(jobRepository.findByMatchIdOrderByIdDesc(1L)).thenReturn(List.of(job));
    }

    @Test
    @DisplayName("Rearma o job em GC_DONE para o worker refazer o download e o parsing")
    void rearmaJobEmGcDone() {
        Long jobId = service.rearmar(1L);

        assertThat(jobId).isEqualTo(7L);
        assertThat(job.getStatus()).isEqualTo(MatchFetchStatus.GC_DONE);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).isNull();
        assertThat(job.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("Copia o CS Rating da partida para o job — sem isso o re-parse perde a faixa de comparação")
    void copiaCsRatingDaPartidaParaOJob() {
        // Os jobs originais foram criados antes da captura de rating existir:
        // o rating vive na Match (posto ali por um recompute manual). O
        // processDemo do worker lê job.csRating, então ele precisa migrar.
        service.rearmar(1L);

        assertThat(job.getCsRating()).isEqualTo(10096);
    }

    @Test
    @DisplayName("Solta as FKs externas antes de apagar a partida")
    void soltaFksAntesDeApagar() {
        service.rearmar(1L);

        InOrder ordem = inOrder(jobRepository, statsRepository, matchRepository);
        // player_match_stats e match_fetch_jobs apontam para matches de fora da
        // arvore de cascade: apagar a Match antes de soltar as duas viola a
        // integridade referencial.
        ordem.verify(jobRepository).saveAllAndFlush(anyList());
        ordem.verify(statsRepository).deleteByMatchId(1L);
        ordem.verify(matchRepository).delete(match);

        assertThat(job.getMatch()).isNull();
    }

    @Test
    @DisplayName("Com mais de um job na mesma partida, rearma o mais recente e solta a FK de todos")
    void rearmaOMaisRecenteQuandoHaVariosJobsNaMesmaPartida() {
        // Duas contas reportando a mesma sessão: MatchJobWorker.analyzeDemo
        // reaproveita a Match já analisada e aponta o segundo job para ela.
        MatchFetchJob jobAntigo = MatchFetchJob.builder()
                .id(3L)
                .steamId64("76561199000000001")
                .shareCode("CSGO-antigo-AAAAA-BBBBB-CCCCC-ddddd")
                .status(MatchFetchStatus.NOTIFIED)
                .demoUrl("http://replay201.valve.net/730/antigo.dem.bz2")
                .match(match)
                .attempts(1)
                .build();

        MatchFetchJob jobRecente = MatchFetchJob.builder()
                .id(9L)
                .steamId64("76561199110265389")
                .shareCode("CSGO-recente-AAAAA-BBBBB-CCCCC-eeeee")
                .status(MatchFetchStatus.NOTIFIED)
                .demoUrl("http://replay201.valve.net/730/recente.dem.bz2")
                .match(match)
                .attempts(0)
                .build();

        // findByMatchIdOrderByIdDesc devolve do mais recente para o mais antigo.
        when(jobRepository.findByMatchIdOrderByIdDesc(1L))
                .thenReturn(List.of(jobRecente, jobAntigo));

        Long jobId = service.rearmar(1L);

        assertThat(jobId).isEqualTo(9L);
        assertThat(jobRecente.getStatus()).isEqualTo(MatchFetchStatus.GC_DONE);
        assertThat(jobAntigo.getMatch()).isNull();
        assertThat(jobRecente.getMatch()).isNull();
        // o job irmão não é rearmado — mantém o próprio status, só perde a Match.
        assertThat(jobAntigo.getStatus()).isEqualTo(MatchFetchStatus.NOTIFIED);
    }

    @Test
    @DisplayName("Recusa quando o job não tem URL de demo — não há de onde re-baixar")
    void recusaSemDemoUrl() {
        job.setDemoUrl(null);

        assertThatThrownBy(() -> service.rearmar(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("URL da demo");

        verify(matchRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Recusa quando a partida não existe")
    void recusaPartidaInexistente() {
        when(matchRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rearmar(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
