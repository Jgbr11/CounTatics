package com.countatic.core.service;

import com.countatic.core.dto.stats.TrendSeriesDTO;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes da série histórica por jogador.
 *
 * <p>O comportamento que mais importa aqui é a <b>ordenação pelo momento em que
 * a partida foi jogada</b>, e não pelo momento em que ela foi analisada. Os
 * dois coincidem no caminho feliz — e é justamente por isso que um teste que
 * não os separe de propósito passaria com a implementação errada.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PlayerTrendServiceTest {

    private static final String STEAM_ID = "76561199110265389";
    private static final String OUTRO = "76561198000000002";

    @Autowired
    private PlayerTrendService trendService;

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

    // ═══════════════════════════════════════════════════════════════

    /**
     * Grava as partidas fora de ordem cronológica de propósito: se o serviço
     * ordenasse pela ordem de inserção (ou por {@code createdAt}), este teste
     * passaria mesmo assim. Ordenando ao contrário do "jogado em", ele falha.
     */
    @Test
    @DisplayName("a série sai em ordem cronológica, pelo momento em que a partida foi jogada")
    void serieSaiEmOrdemCronologica() {
        Instant agora = Instant.now();
        // Inserção: hoje, 10 dias atrás, 5 dias atrás.
        criarDesempenho(agora, 100.0);
        criarDesempenho(agora.minus(10, ChronoUnit.DAYS), 50.0);
        criarDesempenho(agora.minus(5, ChronoUnit.DAYS), 75.0);

        TrendSeriesDTO serie = trendService.serie(STEAM_ID, "adr", 10);

        assertThat(serie.getPontos()).extracting(TrendSeriesDTO.Ponto::getValor)
                .containsExactly(50.0, 75.0, 100.0);
    }

    @Test
    @DisplayName("o limite pega as partidas MAIS RECENTES, não as primeiras encontradas")
    void limitePegaAsMaisRecentes() {
        Instant agora = Instant.now();
        for (int i = 0; i < 6; i++) {
            criarDesempenho(agora.minus(i, ChronoUnit.DAYS), 10.0 * (6 - i));
        }

        TrendSeriesDTO serie = trendService.serie(STEAM_ID, "adr", 3);

        // As 3 mais recentes são i=0,1,2 → valores 60, 50, 40; em ordem
        // cronológica saem 40, 50, 60.
        assertThat(serie.getPontos()).extracting(TrendSeriesDTO.Ponto::getValor)
                .containsExactly(40.0, 50.0, 60.0);
    }

    @Test
    @DisplayName("a série traz só as partidas do jogador pedido")
    void naoMisturaJogadores() {
        Instant agora = Instant.now();
        criarDesempenho(agora, 100.0);
        criarDesempenhoDe(OUTRO, agora.minus(1, ChronoUnit.DAYS), 999.0);

        TrendSeriesDTO serie = trendService.serie(STEAM_ID, "adr", 10);

        assertThat(serie.getPontos()).hasSize(1);
        assertThat(serie.getPontos().get(0).getValor()).isEqualTo(100.0);
    }

    /**
     * Métrica ausente é diferente de zero — foi a correção que gerou toda a
     * regra de "zero medido vs. ausência". O ponto precisa chegar ao gráfico
     * com valor nulo para a linha ser interrompida, em vez de mergulhar até
     * zero num desempenho que nunca foi medido.
     */
    @Test
    @DisplayName("métrica ausente vira ponto com valor nulo, e fica fora da média")
    void metricaAusenteNaoViraZero() {
        Instant agora = Instant.now();
        criarDesempenho(agora.minus(2, ChronoUnit.DAYS), 80.0);
        criarDesempenho(agora.minus(1, ChronoUnit.DAYS), null);
        criarDesempenho(agora, 100.0);

        TrendSeriesDTO serie = trendService.serie(STEAM_ID, "adr", 10);

        assertThat(serie.getPontos()).extracting(TrendSeriesDTO.Ponto::getValor)
                .containsExactly(80.0, null, 100.0);
        // Se o nulo contasse como zero, a média cairia para 60.
        assertThat(serie.getMedia()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("jogador sem histórico devolve série vazia, não erro")
    void semHistoricoDevolveSerieVazia() {
        TrendSeriesDTO serie = trendService.serie("76561198999999999", "adr", 10);

        assertThat(serie.getPontos()).isEmpty();
        assertThat(serie.getMedia()).isNull();
        assertThat(serie.getLabel()).isEqualTo("ADR");
    }

    @Test
    @DisplayName("métrica desconhecida é rejeitada em vez de virar série vazia")
    void metricaDesconhecidaEhRejeitada() {
        assertThatThrownBy(() -> trendService.serie(STEAM_ID, "naoExiste", 10))
                .isInstanceOf(PlayerTrendService.MetricaDesconhecidaException.class);
    }

    /** Sem teto, um limit gigante carregaria o histórico inteiro com um join por linha. */
    @Test
    @DisplayName("o limite é preso ao teto")
    void limiteEhLimitado() {
        Instant agora = Instant.now();
        for (int i = 0; i < 3; i++) {
            criarDesempenho(agora.minus(i, ChronoUnit.DAYS), 10.0);
        }

        // Não estoura nem devolve mais do que existe.
        assertThat(trendService.serie(STEAM_ID, "adr", 100_000).getPontos()).hasSize(3);
        assertThat(trendService.serie(STEAM_ID, "adr", 0).getPontos()).hasSize(1);
    }

    @Test
    @DisplayName("a direção da métrica acompanha a série")
    void direcaoAcompanhaASerie() {
        assertThat(trendService.serie(STEAM_ID, "adr", 10).isMaiorEhMelhor()).isTrue();
        // Única métrica comparável em que menor é melhor.
        assertThat(trendService.serie(STEAM_ID, "deathsPerRound", 10).isMaiorEhMelhor()).isFalse();
    }

    /** Toda métrica comparável precisa ter leitor, senão o endpoint estoura em produção. */
    @Test
    @DisplayName("toda métrica suportada pelo baseline tem leitor na série")
    void todaMetricaComparavelTemLeitor() {
        for (String chave : BaselineService.metricasSuportadas()) {
            assertThat(trendService.serie(STEAM_ID, chave, 1))
                    .as("métrica %s", chave)
                    .isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Resultado da partida
    // ═══════════════════════════════════════════════════════════════

    /**
     * O placar cru "13-8" não diz de quem é. Orientar pelo lado do jogador é o
     * que transforma o número em "vitória por 13-8" ou "derrota por 8-13", e a
     * interface não deveria precisar repetir essa conta.
     */
    @Test
    @DisplayName("o placar chega orientado pelo lado do jogador")
    void placarVemOrientadoPeloLado() {
        Instant agora = Instant.now();
        // Partida 13 (CT) x 8 (TR).
        criarComLado(agora.minus(1, ChronoUnit.DAYS), 90.0, Team.CT, true);
        criarComLado(agora, 90.0, Team.TR, false);

        List<TrendSeriesDTO.Ponto> pontos = trendService.serie(STEAM_ID, "adr", 10).getPontos();

        // Quem jogou de CT vê 13-8; quem jogou de TR vê o mesmo placar invertido.
        assertThat(pontos.get(0).getScoreSelf()).isEqualTo(13);
        assertThat(pontos.get(0).getScoreEnemy()).isEqualTo(8);
        assertThat(pontos.get(0).getWon()).isTrue();

        assertThat(pontos.get(1).getScoreSelf()).isEqualTo(8);
        assertThat(pontos.get(1).getScoreEnemy()).isEqualTo(13);
        assertThat(pontos.get(1).getWon()).isFalse();
    }

    /**
     * Partidas analisadas antes de o resultado passar a ser guardado ficam com
     * o lado nulo. Precisam viajar como "desconhecido" — inventar vitória seria
     * pior do que não pintar a bolinha.
     */
    @Test
    @DisplayName("sem lado registrado, resultado e placar vêm nulos em vez de inventados")
    void semLadoResultadoEhDesconhecido() {
        criarDesempenho(Instant.now(), 90.0);

        TrendSeriesDTO.Ponto p = trendService.serie(STEAM_ID, "adr", 10).getPontos().get(0);

        assertThat(p.getWon()).isNull();
        assertThat(p.getScoreSelf()).isNull();
        assertThat(p.getScoreEnemy()).isNull();
    }

    /** Com 3 partidas no banco não há as 30 amostras que o baseline exige. */
    @Test
    @DisplayName("sem amostra na faixa, a linha de referência da faixa não é enviada")
    void mediaDaFaixaExigeAmostra() {
        criarDesempenho(Instant.now(), 90.0);

        TrendSeriesDTO serie = trendService.serie(STEAM_ID, "adr", 10);

        assertThat(serie.getMediaDaFaixa()).isNull();
        assertThat(serie.getFaixaLabel()).isNull();
    }

    /**
     * As métricas de posicionamento passaram a ser persistidas para virarem
     * histórico. Um erro de mapeamento entre a chave e a coluna não quebra
     * nada visível — devolve série vazia, que parece "jogador sem histórico".
     */
    @Test
    @DisplayName("as métricas de posicionamento chegam à série com o valor gravado")
    void posicionamentoViraHistorico() {
        Match m = matchRepository.save(Match.builder()
                .demoFileHash("hash-pos").demoFileName("t.dem").mapName("de_mirage")
                .durationSeconds(1800).scoreCT(13).scoreTR(8)
                .totalRounds(21).tickRate(64)
                .status(MatchStatus.COMPLETED).playedAt(Instant.now())
                .build());

        statsRepository.save(PlayerMatchStats.builder()
                .match(m).player(jogador).steamId64(STEAM_ID).roundsPlayed(21)
                .closeRangeWinRate(71.0)
                .longRangeWinRate(29.0)
                .earlyDeathRate(31.0)
                .build());

        assertThat(trendService.serie(STEAM_ID, "closeRangeWinRate", 10)
                .getPontos().get(0).getValor()).isEqualTo(71.0);
        assertThat(trendService.serie(STEAM_ID, "longRangeWinRate", 10)
                .getPontos().get(0).getValor()).isEqualTo(29.0);
        assertThat(trendService.serie(STEAM_ID, "earlyDeathRate", 10)
                .getPontos().get(0).getValor()).isEqualTo(31.0);
    }

    /**
     * Morrer menos na entrada é melhor. Sem a direção correta, o percentil
     * premiaria quem mais morre entrando.
     */
    @Test
    @DisplayName("mortes na entrada viajam como métrica invertida")
    void mortesNaEntradaSaoInvertidas() {
        assertThat(trendService.serie(STEAM_ID, "earlyDeathRate", 10).isMaiorEhMelhor()).isFalse();
        assertThat(trendService.serie(STEAM_ID, "longRangeWinRate", 10).isMaiorEhMelhor()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════

    private void criarDesempenho(Instant jogadaEm, Double adr) {
        criarDesempenhoDe(STEAM_ID, jogadaEm, adr, null, null);
    }

    private void criarComLado(Instant jogadaEm, Double adr, Team lado, Boolean venceu) {
        criarDesempenhoDe(STEAM_ID, jogadaEm, adr, lado, venceu);
    }

    private void criarDesempenhoDe(String steamId, Instant jogadaEm, Double adr) {
        criarDesempenhoDe(steamId, jogadaEm, adr, null, null);
    }

    private void criarDesempenhoDe(String steamId, Instant jogadaEm, Double adr,
                                   Team lado, Boolean venceu) {
        Match m = matchRepository.save(Match.builder()
                .demoFileHash("hash-" + steamId + "-" + jogadaEm.toEpochMilli())
                .demoFileName("t.dem")
                .mapName("de_mirage")
                .durationSeconds(1800)
                .scoreCT(13).scoreTR(8)
                .totalRounds(21).tickRate(64)
                .status(MatchStatus.COMPLETED)
                .playedAt(jogadaEm)
                .build());

        Player p = playerRepository.findBySteamId64(steamId)
                .orElseGet(() -> playerRepository.save(
                        Player.builder().steamId64(steamId).displayName(steamId).build()));

        statsRepository.save(PlayerMatchStats.builder()
                .match(m)
                .player(p)
                .steamId64(steamId)
                .roundsPlayed(21)
                .adr(adr)
                .deathsPerRound(0.7)
                .playerSide(lado)
                .won(venceu)
                .build());
    }
}
