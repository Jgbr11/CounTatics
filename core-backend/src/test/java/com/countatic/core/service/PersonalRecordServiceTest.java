package com.countatic.core.service;

import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import com.countatic.core.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes dos recordes pessoais.
 *
 * <p>Dois erros aqui não estouram e produzem resultado plausível: incluir a
 * própria partida na comparação (aí nada nunca é recorde) e tratar toda
 * métrica como "maior é melhor" (aí quem mais morre bate recorde). Os testes
 * atacam os dois diretamente.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonalRecordServiceTest {

    private static final String STEAM_ID = "76561199110265389";
    private static final String MAPA = "de_mirage";

    @Autowired
    private PersonalRecordService service;

    @Autowired
    private PlayerMatchStatsRepository statsRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRepository playerRepository;

    private Player jogador;

    @BeforeEach
    void preparar() {
        statsRepository.deleteAll();
        matchRepository.deleteAll();
        playerRepository.deleteAll();

        jogador = playerRepository.save(Player.builder()
                .steamId64(STEAM_ID).displayName("JGBR11").build());
    }

    /**
     * Sem histórico suficiente, tudo seria recorde — e o ícone viraria enfeite
     * que ninguém lê.
     */
    @Test
    @DisplayName("com menos de três partidas no mapa, nenhum recorde é anunciado")
    void exigeHistoricoMinimo() {
        criar(MAPA, 1, 70.0, 0.9);
        criar(MAPA, 2, 75.0, 0.8);
        Match atual = criar(MAPA, 3, 200.0, 0.1);

        var r = service.recordes(MAPA, atual.getId(),
                Map.of(STEAM_ID, Map.of("adr", 200.0, "deathsPerRound", 0.1)));

        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("superar o melhor anterior no mapa é recorde")
    void superarEhRecorde() {
        criar(MAPA, 1, 70.0, 0.9);
        criar(MAPA, 2, 85.0, 0.8);
        criar(MAPA, 3, 78.0, 0.85);
        Match atual = criar(MAPA, 4, 99.0, 0.7);

        var r = service.recordes(MAPA, atual.getId(),
                Map.of(STEAM_ID, Map.of("adr", 99.0)));

        assertThat(r.get(STEAM_ID)).contains("adr");
    }

    /**
     * Em mortes por round o recorde é o MÍNIMO. Tratar tudo como máximo
     * premiaria justamente a pior partida.
     */
    @Test
    @DisplayName("em métrica invertida, o recorde é o menor valor")
    void metricaInvertidaUsaMinimo() {
        criar(MAPA, 1, 70.0, 0.90);
        criar(MAPA, 2, 70.0, 0.75);
        criar(MAPA, 3, 70.0, 0.80);

        Match melhor = criar(MAPA, 4, 70.0, 0.60);
        assertThat(service.recordes(MAPA, melhor.getId(),
                Map.of(STEAM_ID, Map.of("deathsPerRound", 0.60))).get(STEAM_ID))
                .contains("deathsPerRound");

        Match pior = criar(MAPA, 5, 70.0, 1.20);
        var r = service.recordes(MAPA, pior.getId(),
                Map.of(STEAM_ID, Map.of("deathsPerRound", 1.20)));
        assertThat(r.getOrDefault(STEAM_ID, Set.of())).doesNotContain("deathsPerRound");
    }

    /**
     * Se a partida atual entrasse na comparação, ela seria o próprio recorde a
     * bater e nada nunca seria marcado.
     */
    @Test
    @DisplayName("a partida atual fica fora da própria comparação")
    void partidaAtualNaoCompetiConsigo() {
        criar(MAPA, 1, 70.0, 0.9);
        criar(MAPA, 2, 72.0, 0.9);
        criar(MAPA, 3, 71.0, 0.9);
        Match atual = criar(MAPA, 4, 120.0, 0.9);

        var r = service.recordes(MAPA, atual.getId(),
                Map.of(STEAM_ID, Map.of("adr", 120.0)));

        assertThat(r.get(STEAM_ID)).contains("adr");
    }

    @Test
    @DisplayName("o histórico é por mapa: outro mapa não conta")
    void historicoEhPorMapa() {
        criar("de_nuke", 1, 150.0, 0.5);
        criar("de_nuke", 2, 160.0, 0.5);
        criar("de_nuke", 3, 155.0, 0.5);

        Match atual = criar(MAPA, 4, 90.0, 0.9);

        // Só uma partida na Mirage (a atual, excluída) — sem histórico no mapa.
        var r = service.recordes(MAPA, atual.getId(),
                Map.of(STEAM_ID, Map.of("adr", 90.0)));

        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("empatar a própria marca não é recorde")
    void empateNaoEhRecorde() {
        criar(MAPA, 1, 70.0, 0.9);
        criar(MAPA, 2, 90.0, 0.9);
        criar(MAPA, 3, 80.0, 0.9);
        Match atual = criar(MAPA, 4, 90.0, 0.9);

        var r = service.recordes(MAPA, atual.getId(),
                Map.of(STEAM_ID, Map.of("adr", 90.0)));

        assertThat(r.getOrDefault(STEAM_ID, Set.of())).doesNotContain("adr");
    }

    /** Ausente não é zero — aqui zero venceria todo recorde invertido. */
    @Test
    @DisplayName("métrica não medida na partida atual não vira recorde")
    void ausenteNaoViraRecorde() {
        criar(MAPA, 1, 70.0, 0.9);
        criar(MAPA, 2, 75.0, 0.8);
        criar(MAPA, 3, 72.0, 0.85);
        Match atual = criar(MAPA, 4, 99.0, 0.7);

        // deathsPerRound ausente do mapa da partida atual.
        var r = service.recordes(MAPA, atual.getId(),
                Map.of(STEAM_ID, Map.of("adr", 99.0)));

        assertThat(r.get(STEAM_ID)).doesNotContain("deathsPerRound");
    }

    // ═══════════════════════════════════════════════════════════════

    private Match criar(String mapa, int n, Double adr, Double deathsPerRound) {
        Match m = matchRepository.save(Match.builder()
                .demoFileHash("hash-" + mapa + "-" + n).demoFileName("t.dem")
                .mapName(mapa).durationSeconds(1800)
                .scoreCT(13).scoreTR(8).totalRounds(21).tickRate(64)
                .status(MatchStatus.COMPLETED)
                .playedAt(Instant.now().minus(30 - n, ChronoUnit.DAYS))
                .build());

        statsRepository.save(PlayerMatchStats.builder()
                .match(m).player(jogador).steamId64(STEAM_ID).roundsPlayed(21)
                .adr(adr).deathsPerRound(deathsPerRound)
                .build());

        return m;
    }
}
