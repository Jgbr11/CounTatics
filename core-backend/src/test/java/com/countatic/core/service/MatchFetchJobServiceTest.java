package com.countatic.core.service;

import com.countatic.core.entity.FetchSource;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.repository.MatchFetchJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes da fila de jobs — o mecanismo que impede uma partida de ser perdida
 * quando algo falha no meio do processamento.
 */
@SpringBootTest
@ActiveProfiles("test")
class MatchFetchJobServiceTest {

    private static final String STEAM_ID = "76561199110265389";
    private static final String SHARE_CODE = "CSGO-Pf6Xz-AXpEG-GdzKr-GyWv6-pdueM";

    @Autowired
    private MatchFetchJobService jobService;

    @Autowired
    private MatchFetchJobRepository jobRepository;

    @BeforeEach
    void limpar() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("enqueueIfAbsent cria o job na primeira chamada e não duplica na segunda")
    void naoDuplicaJob() {
        Optional<MatchFetchJob> primeiro = jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, FetchSource.POLL);
        Optional<MatchFetchJob> segundo = jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, FetchSource.GSI);

        assertTrue(primeiro.isPresent(), "a primeira chamada deve criar o job");
        assertTrue(segundo.isEmpty(), "a segunda chamada deve ser no-op");
        assertEquals(1, jobRepository.count());
        assertEquals(FetchSource.POLL, jobRepository.findAll().get(0).getSource(),
                "quem inseriu primeiro define a origem");
    }

    /**
     * A constraint única {@code (steamId64, shareCode)} é o árbitro entre o GSI
     * e o polling: os dois podem descobrir a mesma partida ao mesmo tempo, e
     * apenas um job pode existir — senão o jogador recebe relatório duplicado.
     */
    @Test
    @DisplayName("corrida entre GSI e polling produz exatamente um job")
    void corridaProduzUmUnicoJob() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> criou = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            FetchSource origem = (i % 2 == 0) ? FetchSource.POLL : FetchSource.GSI;
            criou.add(pool.submit(() -> {
                largada.await();
                try {
                    return jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, origem).isPresent();
                } catch (Exception e) {
                    // A violação de constraint deve ser tratada internamente.
                    return false;
                }
            }));
        }

        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long criados = criou.stream().filter(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return false;
            }
        }).count();

        assertEquals(1, jobRepository.count(), "só um job pode existir para o mesmo share code");
        assertEquals(1, criados, "só uma thread pode reportar criação");
    }

    @Test
    @DisplayName("markFailed incrementa tentativas e agenda a próxima no futuro")
    void markFailedAplicaBackoff() {
        MatchFetchJob job = jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, FetchSource.POLL).orElseThrow();

        Instant antes = Instant.now();
        jobService.markFailed(job, "GC indisponível");

        MatchFetchJob recarregado = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(MatchFetchStatus.FAILED, recarregado.getStatus());
        assertEquals(1, recarregado.getAttempts());
        assertEquals("GC indisponível", recarregado.getLastError());
        assertTrue(recarregado.getNextAttemptAt().isAfter(antes),
                "a próxima tentativa deve ficar no futuro, não imediata");
    }

    @Test
    @DisplayName("o backoff cresce a cada nova falha")
    void backoffCresce() {
        MatchFetchJob job = jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, FetchSource.POLL).orElseThrow();

        jobService.markFailed(job, "falha 1");
        Instant primeira = jobRepository.findById(job.getId()).orElseThrow().getNextAttemptAt();

        MatchFetchJob j2 = jobRepository.findById(job.getId()).orElseThrow();
        jobService.markFailed(j2, "falha 2");
        Instant segunda = jobRepository.findById(job.getId()).orElseThrow().getNextAttemptAt();

        assertTrue(segunda.isAfter(primeira),
                "a segunda falha deve esperar mais que a primeira");
    }

    @Test
    @DisplayName("findRunnable ignora job cujo backoff ainda não venceu")
    void respeitaBackoff() {
        MatchFetchJob job = jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, FetchSource.POLL).orElseThrow();
        jobService.markFailed(job, "erro");

        assertTrue(jobService.findRunnable(10).isEmpty(),
                "job em backoff não pode ser reprocessado imediatamente");

        // Adianta o relógio do job.
        MatchFetchJob j = jobRepository.findById(job.getId()).orElseThrow();
        j.setNextAttemptAt(Instant.now().minusSeconds(1));
        jobRepository.save(j);

        assertEquals(1, jobService.findRunnable(10).size(),
                "vencido o backoff, o job volta para a fila");
    }

    @Test
    @DisplayName("findRunnable não devolve jobs em estado terminal")
    void ignoraTerminais() {
        MatchFetchJob job = jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, FetchSource.POLL).orElseThrow();
        jobService.updateStatus(job, MatchFetchStatus.NOTIFIED);

        assertTrue(jobService.findRunnable(10).isEmpty());

        MatchFetchJob outro = jobService.enqueueIfAbsent(STEAM_ID, "CSGO-outro", FetchSource.POLL).orElseThrow();
        jobService.markDemoExpired(outro, "demo expirou no CDN");

        assertTrue(jobService.findRunnable(10).isEmpty(),
                "DEMO_EXPIRED é terminal: retentar nunca traria o arquivo de volta");
    }

    @Test
    @DisplayName("findExhausted encontra quem esgotou as tentativas")
    void encontraEsgotados() {
        MatchFetchJob job = jobService.enqueueIfAbsent(STEAM_ID, SHARE_CODE, FetchSource.POLL).orElseThrow();

        for (int i = 0; i < MatchFetchJobService.MAX_ATTEMPTS; i++) {
            MatchFetchJob j = jobRepository.findById(job.getId()).orElseThrow();
            jobService.markFailed(j, "falha " + i);
        }

        assertTrue(jobService.findRunnable(10).isEmpty(),
                "quem esgotou tentativas sai da fila normal");
        assertEquals(1, jobService.findExhausted().size(),
                "e deve ser encontrado para encerramento");
    }

    @Test
    @DisplayName("sondagem por share code do GSI tem backoff próprio e desiste ao fim")
    void sondagemDesisteNoFim() {
        MatchFetchJob job = jobService.enqueueAwaitingShareCode(
                STEAM_ID, "de_mirage", 13, 8, "{}");

        assertEquals(MatchFetchStatus.AWAITING_SHARECODE, job.getStatus());

        // Esgota todas as sondagens.
        for (int i = 0; i < 10; i++) {
            MatchFetchJob j = jobRepository.findById(job.getId()).orElseThrow();
            if (j.getStatus() != MatchFetchStatus.AWAITING_SHARECODE) break;
            jobService.rescheduleShareCodeProbe(j);
        }

        MatchFetchJob fim = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(MatchFetchStatus.ABANDONED, fim.getStatus(),
                "após as sondagens, o job é encerrado em vez de sondar para sempre");
    }

    @Test
    @DisplayName("shareCode nulo não colide na constraint única")
    void varriasSondagensPendentesPodemCoexistir() {
        // Em MySQL, NULLs não colidem numa unique key — proposital: várias
        // partidas podem estar aguardando share code ao mesmo tempo.
        jobService.enqueueAwaitingShareCode(STEAM_ID, "de_mirage", 13, 8, "{}");
        jobService.enqueueAwaitingShareCode(STEAM_ID, "de_inferno", 10, 13, "{}");

        assertEquals(2, jobRepository.count());
    }
}
