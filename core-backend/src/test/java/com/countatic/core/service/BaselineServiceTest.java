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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes da comparação por faixa.
 *
 * O comportamento mais importante aqui não é calcular o percentil — é
 * <b>recusar-se a calcular</b> quando a amostra é pequena demais.
 */
@SpringBootTest
@ActiveProfiles("test")
class BaselineServiceTest {

    @Autowired
    private BaselineService baselineService;

    @Autowired
    private PlayerMatchStatsRepository statsRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRepository playerRepository;

    /** Igual ao default de {@code countatic.baseline.min-sample}. */
    private static final int AMOSTRA_MINIMA = 30;

    private Match partida;

    @BeforeEach
    void preparar() {
        statsRepository.deleteAll();
        matchRepository.deleteAll();
        playerRepository.deleteAll();

        partida = matchRepository.save(Match.builder()
                .demoFileHash("hash-teste")
                .demoFileName("teste.dem")
                .mapName("de_teste")
                .durationSeconds(1800)
                .scoreCT(13).scoreTR(8)
                .totalRounds(21).tickRate(64)
                .status(MatchStatus.COMPLETED)
                .playedAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("sem faixa (rating ausente) não devolve comparação")
    void semFaixaNaoCompara() {
        var r = baselineService.comparar(null, Map.of("adr", 80.0));

        assertFalse(r.isAmostraSuficiente());
        assertTrue(r.getMetricas().isEmpty());
        assertTrue(r.getAviso().contains("Sem CS Rating"));
    }

    /**
     * O comportamento que mais importa: um percentil calculado sobre poucas
     * amostras parece preciso e não é. Melhor não mostrar nada.
     */
    @Test
    @DisplayName("amostra abaixo do mínimo NÃO devolve percentil")
    void amostraPequenaNaoCompara() {
        criarDesempenhos(RankTier.AZUL, 10, 70.0);

        var r = baselineService.comparar(RankTier.AZUL, Map.of("adr", 80.0));

        assertFalse(r.isAmostraSuficiente(), "10 amostras não bastam para um percentil");
        assertTrue(r.getMetricas().isEmpty(), "nenhuma métrica pode ser comparada");
        assertEquals(10, r.getAmostraTotal());
        assertTrue(r.getAviso().contains("Amostra insuficiente"));
    }

    @Test
    @DisplayName("com amostra suficiente, calcula o percentil")
    void amostraSuficienteCompara() {
        // 40 desempenhos com ADR variando de 50 a 89.
        for (int i = 0; i < 40; i++) {
            criarDesempenho(RankTier.AZUL, 50.0 + i);
        }

        var r = baselineService.comparar(RankTier.AZUL, Map.of("adr", 89.0));

        assertTrue(r.isAmostraSuficiente());
        assertEquals(40, r.getAmostraTotal());

        var adr = r.getMetricas().get("adr");
        assertNotNull(adr);
        assertEquals(100.0, adr.getPercentil(), 0.01,
                "o maior valor da amostra deve ficar no topo");
        assertEquals(69.5, adr.getMedia(), 0.01, "média de 50..89");
    }

    @Test
    @DisplayName("valor mediano fica perto do percentil 50")
    void valorMedianoFicaNoMeio() {
        for (int i = 0; i < 40; i++) {
            criarDesempenho(RankTier.AZUL, 50.0 + i);
        }

        var r = baselineService.comparar(RankTier.AZUL, Map.of("adr", 69.0));
        double p = r.getMetricas().get("adr").getPercentil();

        assertTrue(p > 40 && p < 60, "esperado perto de 50, veio " + p);
    }

    /**
     * Mortes por round é a única métrica em que menor é melhor. Sem a inversão,
     * o percentil premiaria justamente quem morre mais.
     */
    @Test
    @DisplayName("em 'mortes por round', MENOS é melhor — o percentil é invertido")
    void metricaInvertida() {
        for (int i = 0; i < 40; i++) {
            criarDesempenhoComMortes(RankTier.AZUL, 0.5 + i * 0.02);
        }

        // 0.5 é o MENOR número de mortes da amostra = o melhor desempenho.
        var melhor = baselineService.comparar(RankTier.AZUL, Map.of("deathsPerRound", 0.5));
        var pior = baselineService.comparar(RankTier.AZUL, Map.of("deathsPerRound", 1.3));

        double pMelhor = melhor.getMetricas().get("deathsPerRound").getPercentil();
        double pPior = pior.getMetricas().get("deathsPerRound").getPercentil();

        assertTrue(pMelhor > pPior,
                "morrer menos tem que dar percentil MAIOR (veio melhor=" + pMelhor + " pior=" + pPior + ")");
    }

    @Test
    @DisplayName("faixas são independentes entre si")
    void faixasNaoSeMisturam() {
        criarDesempenhos(RankTier.AZUL, 40, 70.0);
        criarDesempenhos(RankTier.OURO, 40, 120.0);

        var azul = baselineService.comparar(RankTier.AZUL, Map.of("adr", 70.0));
        var ouro = baselineService.comparar(RankTier.OURO, Map.of("adr", 70.0));

        assertEquals(70.0, azul.getMetricas().get("adr").getMedia(), 0.01);
        assertEquals(120.0, ouro.getMetricas().get("adr").getMedia(), 0.01,
                "a média do Ouro não pode ser contaminada pelo Azul");
    }

    @Test
    @DisplayName("métrica desconhecida é ignorada em vez de virar SQL")
    void metricaDesconhecidaEhIgnorada() {
        criarDesempenhos(RankTier.AZUL, 40, 70.0);

        // Tentativa de injeção via nome de métrica: precisa ser simplesmente
        // ignorada, já que só nomes da whitelist chegam à consulta.
        var r = baselineService.comparar(RankTier.AZUL,
                Map.of("adr; DROP TABLE player_match_stats", 1.0));

        assertTrue(r.getMetricas().isEmpty());
        assertEquals(40, statsRepository.count(), "a tabela continua intacta");
    }

    @Test
    @DisplayName("métrica sem dados suficientes na faixa é omitida")
    void metricaSemDadosEhOmitida() {
        // 40 linhas com ADR, mas nenhuma com flashEfficiency.
        criarDesempenhos(RankTier.AZUL, 40, 70.0);

        var r = baselineService.comparar(RankTier.AZUL,
                Map.of("adr", 70.0, "flashEfficiency", 55.0));

        assertNotNull(r.getMetricas().get("adr"));
        assertNull(r.getMetricas().get("flashEfficiency"),
                "sem dados da métrica, não há o que comparar");
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private void criarDesempenhos(RankTier faixa, int quantidade, double adr) {
        for (int i = 0; i < quantidade; i++) {
            criarDesempenho(faixa, adr);
        }
    }

    private void criarDesempenho(RankTier faixa, double adr) {
        Player p = novoJogador();
        statsRepository.save(PlayerMatchStats.builder()
                .match(partida).player(p).steamId64(p.getSteamId64())
                .rankTier(faixa).csRating(faixa.getMin() + 100)
                .roundsPlayed(21).adr(adr)
                .build());
    }

    private void criarDesempenhoComMortes(RankTier faixa, double mortesPorRound) {
        Player p = novoJogador();
        statsRepository.save(PlayerMatchStats.builder()
                .match(partida).player(p).steamId64(p.getSteamId64())
                .rankTier(faixa).csRating(faixa.getMin() + 100)
                .roundsPlayed(21).deathsPerRound(mortesPorRound)
                .build());
    }

    private int seq = 0;

    private Player novoJogador() {
        // A constraint única é (match, player), então cada linha precisa de
        // um jogador distinto.
        String id = String.format("7656119%010d", seq++);
        return playerRepository.save(Player.builder()
                .steamId64(id).displayName("J" + seq).build());
    }
}
