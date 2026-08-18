package com.countatic.core.strategy;

import com.countatic.core.dto.stats.Insight;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.impl.PositioningStatStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Testes das métricas de posicionamento.
 *
 * <p>Monta duelos sintéticos com posições exatas, porque o ponto aqui é a
 * <b>conversão e a classificação</b>: uma distância calculada em unidades de
 * jogo e exibida como metros erra por um fator de 52 sem quebrar nada
 * visível.</p>
 */
class PositioningStatStrategyTest {

    private static final int TICK_RATE = 64;

    /** Uma unidade Hammer = 1,905 cm, então um metro tem ~52,5 unidades. */
    private static final double UNID_POR_M = 52.5;

    private PositioningStatStrategy strategy;
    private Player eu;
    private Player inimigo;
    private Player aliado;

    @BeforeEach
    void preparar() {
        strategy = new PositioningStatStrategy();
        eu = jogador(1L, "EU");
        inimigo = jogador(2L, "INIMIGO");
        aliado = jogador(3L, "ALIADO");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Distância
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a distância do duelo é convertida de unidades para metros")
    void distanciaEmMetros() {
        Round r = round(1, 0);
        // 20 metros exatos no eixo X.
        r.addEvent(kill(eu, inimigo, 0, 0, 20 * UNID_POR_M, 0, 100));
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getMetrics().get("avgKillDistance")).isCloseTo(20.0, within(0.1));
    }

    /**
     * Separar as duas é o diagnóstico. A média das duas juntas esconderia
     * exatamente o padrão "mato de perto, morro de longe".
     */
    @Test
    @DisplayName("distância das kills e das mortes são medidas separadamente")
    void killEMorteSaoSeparadas() {
        Round r = round(1, 0);
        r.addEvent(kill(eu, inimigo, 0, 0, 5 * UNID_POR_M, 0, 100));      // matei de 5 m
        r.addEvent(kill(inimigo, eu, 0, 0, 30 * UNID_POR_M, 0, 200));     // morri de 30 m
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getMetrics().get("avgKillDistance")).isCloseTo(5.0, within(0.1));
        assertThat(res.getMetrics().get("avgDeathDistance")).isCloseTo(30.0, within(0.1));
    }

    @Test
    @DisplayName("a taxa por faixa só sai com duelos suficientes")
    void taxaExigeAmostraMinima() {
        Round r = round(1, 0);
        // Dois duelos curtos apenas: abaixo do mínimo de 3.
        r.addEvent(kill(eu, inimigo, 0, 0, 3 * UNID_POR_M, 0, 100));
        r.addEvent(kill(eu, inimigo, 0, 0, 4 * UNID_POR_M, 0, 200));
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        // "100% de vitória" em dois duelos é ruído com cara de estatística.
        assertThat(res.getMetrics()).doesNotContainKey("closeRangeWinRate");
    }

    @Test
    @DisplayName("a taxa de faixa conta vitórias e derrotas da mesma distância")
    void taxaPorFaixa() {
        Round r = round(1, 0);
        // Três curtos: dois ganhos, um perdido → 66,7%.
        r.addEvent(kill(eu, inimigo, 0, 0, 3 * UNID_POR_M, 0, 100));
        r.addEvent(kill(eu, inimigo, 0, 0, 4 * UNID_POR_M, 0, 200));
        r.addEvent(kill(inimigo, eu, 0, 0, 5 * UNID_POR_M, 0, 300));
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getMetrics().get("closeRangeWinRate")).isCloseTo(66.67, within(0.1));
    }

    /** Fogo amigo não diz nada sobre escolha de posição. */
    @Test
    @DisplayName("fogo amigo não entra nos duelos")
    void fogoAmigoNaoEhDuelo() {
        Round r = round(1, 0);
        MatchEvent tk = kill(eu, aliado, 0, 0, 5 * UNID_POR_M, 0, 100);
        tk.setVictimSide(Team.CT);   // mesmo lado do ator
        r.addEvent(tk);
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getMetrics().get("totalDuels")).isEqualTo(0.0);
    }

    /** Kill por bomba ou queda não tem posição, e não é duelo. */
    @Test
    @DisplayName("evento sem posição não entra na distância")
    void semPosicaoNaoEntra() {
        Round r = round(1, 0);
        MatchEvent semPos = MatchEvent.builder()
                .eventType(EventType.KILL).actor(eu).victim(inimigo).tick(100)
                .actorSide(Team.CT).victimSide(Team.TR).build();
        r.addEvent(semPos);
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getMetrics()).doesNotContainKey("avgKillDistance");
        assertThat(res.getMetrics().get("totalDuels")).isEqualTo(0.0);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Momento da morte
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("o segundo da morte é medido a partir do início do round")
    void segundoDaMorte() {
        // Round começa no tick 1000; morte no 1640 = 10 s a 64 tick.
        Round r = round(1, 1000);
        r.addEvent(kill(inimigo, eu, 0, 0, 10 * UNID_POR_M, 0, 1640));
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getMetrics().get("avgDeathTimeSeconds")).isCloseTo(10.0, within(0.1));
        assertThat(res.getMetrics().get("earlyDeathRate")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("morrer sempre na entrada vira AVISO")
    void mortesNaEntradaViramAviso() {
        Round r1 = round(1, 0);
        r1.addEvent(kill(inimigo, eu, 0, 0, 10 * UNID_POR_M, 0, 320));   // 5 s
        Round r2 = round(2, 10000);
        r2.addEvent(kill(inimigo, eu, 0, 0, 10 * UNID_POR_M, 0, 10640)); // 10 s
        Match m = partida(r1, r2);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getInsights().get("deathTiming").gravidade())
                .isEqualTo(Insight.Severidade.AVISO);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Insight de faixa — o motivo desta strategy existir
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ganhar de perto e perder de longe vira AVISO com o diagnóstico")
    void desequilibrioDeDistanciaViraAviso() {
        Round r = round(1, 0);
        // 3 duelos curtos vencidos.
        for (int i = 0; i < 3; i++) {
            r.addEvent(kill(eu, inimigo, 0, 0, 5 * UNID_POR_M, 0, 100 + i));
        }
        // 3 duelos longos perdidos.
        for (int i = 0; i < 3; i++) {
            r.addEvent(kill(inimigo, eu, 0, 0, 40 * UNID_POR_M, 0, 200 + i));
        }
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);
        Insight i = res.getInsights().get("duelRange");

        assertThat(i.gravidade()).isEqualTo(Insight.Severidade.AVISO);
        assertThat(i.texto()).contains("não é mira");
    }

    /**
     * Sem as duas pontas não há comparação. Publicar um insight de "faixa" com
     * uma faixa só seria constatação, e disso os cards já dão conta.
     */
    @Test
    @DisplayName("sem duelos longos, o insight de faixa não é publicado")
    void semAsDuasPontasNaoPublicaInsight() {
        Round r = round(1, 0);
        for (int i = 0; i < 4; i++) {
            r.addEvent(kill(eu, inimigo, 0, 0, 5 * UNID_POR_M, 0, 100 + i));
        }
        Match m = partida(r);

        assertThat(strategy.calculate(m, eu).getInsights()).doesNotContainKey("duelRange");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Altura
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("morte de inimigo elevado conta como morte por cima")
    void morteDeCima() {
        Round r = round(1, 0);
        for (int i = 0; i < 3; i++) {
            // Inimigo 100 unidades acima — bem além do limiar de 64.
            MatchEvent e = kill(inimigo, eu, 0, 0, 10 * UNID_POR_M, 0, 100 + i);
            e.setActorPositionZ(100.0);
            e.setVictimPositionZ(0.0);
            r.addEvent(e);
        }
        Match m = partida(r);

        PlayerStatResult res = strategy.calculate(m, eu);

        assertThat(res.getMetrics().get("deathsFromAboveRate")).isEqualTo(100.0);
        assertThat(res.getInsights()).containsKey("highGround");
    }

    @Test
    @DisplayName("desnível pequeno não conta como vantagem de altura")
    void degrauNaoEhVantagem() {
        Round r = round(1, 0);
        for (int i = 0; i < 3; i++) {
            MatchEvent e = kill(inimigo, eu, 0, 0, 10 * UNID_POR_M, 0, 100 + i);
            e.setActorPositionZ(30.0);   // abaixo do limiar de 64
            e.setVictimPositionZ(0.0);
            r.addEvent(e);
        }
        Match m = partida(r);

        assertThat(strategy.calculate(m, eu).getMetrics().get("deathsFromAboveRate"))
                .isEqualTo(0.0);
    }

    // ═══════════════════════════════════════════════════════════════

    private static Match partida(Round... rounds) {
        Match m = Match.builder()
                .id(1L).mapName("de_mirage")
                .totalRounds(rounds.length).tickRate(TICK_RATE)
                .build();
        for (Round r : rounds) m.addRound(r);
        return m;
    }

    private static Round round(int numero, int startTick) {
        return Round.builder().id((long) numero).roundNumber(numero).startTick(startTick).build();
    }

    private static Player jogador(Long id, String nome) {
        return Player.builder().id(id).steamId64("7656119900000000" + id)
                .displayName(nome).build();
    }

    /** Kill com posições explícitas: (ax,ay) do ator e (vx,vy) da vítima. */
    private static MatchEvent kill(Player ator, Player vitima,
                                   double ax, double ay, double vx, double vy, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.KILL).actor(ator).victim(vitima).tick(tick)
                .actorSide(Team.CT).victimSide(Team.TR)
                .actorPositionX(ax).actorPositionY(ay).actorPositionZ(0.0)
                .victimPositionX(vx).victimPositionY(vy).victimPositionZ(0.0)
                .build();
    }
}
