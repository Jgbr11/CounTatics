package com.countatic.core.strategy;

import com.countatic.core.dto.stats.Insight;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.impl.ImpactStatStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes das métricas de impacto.
 *
 * Monta partidas sintéticas evento a evento — cada cenário isola exatamente
 * uma regra (janela de trade, primeiro duelo, último vivo), o que seria
 * impossível de garantir usando uma demo real.
 */
class ImpactStatStrategyTest {

    private static final int TICK_RATE = 64;

    private ImpactStatStrategy strategy;
    private Player eu;
    private Player aliado;
    private Player inimigo1;
    private Player inimigo2;

    @BeforeEach
    void preparar() {
        strategy = new ImpactStatStrategy();
        eu = jogador(1L, "EU");
        aliado = jogador(2L, "ALIADO");
        inimigo1 = jogador(3L, "INIMIGO1");
        inimigo2 = jogador(4L, "INIMIGO2");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADR
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ADR soma o dano e divide pelo total de rounds")
    void adrCalculaMediaPorRound() {
        Round r1 = round(1, Team.CT);
        r1.addEvent(dano(eu, inimigo1, 100, 100));
        r1.addEvent(dano(eu, inimigo2, 50, 200));

        Round r2 = round(2, Team.CT);
        r2.addEvent(dano(eu, inimigo1, 30, 100));

        Match match = partida(2, r1, r2);
        var m = calcular(match).getMetrics();

        assertEquals(180.0, m.get("totalDamage"));
        assertEquals(90.0, m.get("adr"), "180 de dano em 2 rounds = 90 de ADR");
    }

    @Test
    @DisplayName("dano em aliado NÃO conta para o ADR")
    void fogoAmigoNaoContaNoAdr() {
        Round r = round(1, Team.CT);
        r.addEvent(dano(eu, inimigo1, 100, 100));
        // Fogo amigo: mesmo lado. Não é contribuição.
        MatchEvent friendly = dano(eu, aliado, 80, 200);
        friendly.setVictimSide(Team.CT);
        r.addEvent(friendly);

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(100.0, m.get("totalDamage"), "só o dano em inimigo conta");
    }

    /**
     * O denominador do ADR existe (1 round jogado), então 0 de dano é 0.0
     * <b>medido</b> e precisa continuar sendo publicado — omitir aqui apagaria
     * desempenho real de quem jogou mal.
     */
    @Test
    @DisplayName("partida sem dano não quebra e devolve ADR zero")
    void semDanoNaoQuebra() {
        var m = calcular(partida(1, round(1, Team.CT))).getMetrics();
        assertTrue(m.containsKey("adr"), "com round jogado o denominador existe");
        assertEquals(0.0, m.get("adr"));
    }

    /**
     * Sem round nenhum não há "dano por round" a calcular. Publicar 0.0 colocaria
     * a linha na base de comparação do BaselineService como se fosse desempenho
     * real; a chave ausente vira NULL e sai da comparação.
     */
    @Test
    @DisplayName("sem rounds o ADR não é publicado — 0/0 não é 0")
    void semRoundsNaoPublicaAdr() {
        var resultado = calcular(partida(0));

        assertFalse(resultado.getMetrics().containsKey("adr"));
        assertFalse(resultado.getInsights().containsKey("adr"));
        // O absoluto continua: dano total 0 é fato medido.
        assertEquals(0.0, resultado.getMetrics().get("totalDamage"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRADE KILLS
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("matar o algoz de um aliado dentro da janela conta como trade")
    void tradeKillDentroDaJanela() {
        Round r = round(1, Team.CT);
        // Inimigo mata meu aliado…
        r.addEvent(kill(inimigo1, aliado, Team.TR, Team.CT, 1000));
        // …e eu mato o inimigo 2s depois (128 ticks a 64 tick).
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 1000 + 2 * TICK_RATE));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(1.0, m.get("tradeKills"));
    }

    @Test
    @DisplayName("fora da janela de 5s NÃO é trade")
    void foraDaJanelaNaoEhTrade() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(inimigo1, aliado, Team.TR, Team.CT, 1000));
        // 8 segundos depois: a morte do aliado já não é a causa deste duelo.
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 1000 + 8 * TICK_RATE));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(0.0, m.get("tradeKills"));
    }

    @Test
    @DisplayName("a janela acompanha o tick rate do servidor")
    void janelaRespeitaTickRate() {
        // Em 128 tick, 2 segundos = 256 ticks. Se a janela fosse fixa em ticks
        // (e não em segundos), este trade legítimo seria descartado.
        Round r = round(1, Team.CT);
        r.addEvent(kill(inimigo1, aliado, Team.TR, Team.CT, 1000));
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 1000 + 2 * 128));

        Match match = partida(1, r);
        match.setTickRate(128);

        var m = calcular(match).getMetrics();
        assertEquals(1.0, m.get("tradeKills"), "2s a 128 tick continua dentro da janela");
    }

    @Test
    @DisplayName("matar quem não matou aliado nenhum não é trade")
    void killComumNaoEhTrade() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 1000));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(0.0, m.get("tradeKills"));
    }

    @Test
    @DisplayName("minha morte vingada por aliado conta como tradedDeath")
    void morteVingadaPorAliado() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(inimigo1, eu, Team.TR, Team.CT, 1000));
        r.addEvent(kill(aliado, inimigo1, Team.CT, Team.TR, 1000 + 2 * TICK_RATE));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(1.0, m.get("tradedDeaths"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  OPENING DUELS
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("vencer a primeira kill do round conta como opening vencido")
    void openingVencido() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 1000));
        r.addEvent(kill(inimigo2, aliado, Team.TR, Team.CT, 2000));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(1.0, m.get("openingDuels"));
        assertEquals(1.0, m.get("openingDuelsWon"));
        assertEquals(100.0, m.get("openingDuelWinRate"));
    }

    @Test
    @DisplayName("morrer na primeira kill conta como opening perdido")
    void openingPerdido() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(inimigo1, eu, Team.TR, Team.CT, 1000));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(1.0, m.get("openingDuels"));
        assertEquals(0.0, m.get("openingDuelsWon"));
    }

    @Test
    @DisplayName("não participar do primeiro duelo não conta")
    void semParticiparDoOpening() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(inimigo1, aliado, Team.TR, Team.CT, 1000));
        // Minha kill vem depois — não é o duelo de abertura.
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 5000));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(0.0, m.get("openingDuels"));
    }

    @Test
    @DisplayName("a ordem é definida pelo tick, não pela ordem de inserção")
    void ordenaPorTick() {
        Round r = round(1, Team.CT);
        // Inseridos fora de ordem de propósito.
        r.addEvent(kill(inimigo1, aliado, Team.TR, Team.CT, 5000));
        r.addEvent(kill(eu, inimigo2, Team.CT, Team.TR, 1000)); // esta é a primeira

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(1.0, m.get("openingDuelsWon"), "o opening é a kill de MENOR tick");
    }

    // ═══════════════════════════════════════════════════════════════
    //  CLUTCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("último vivo que vence o round conta como clutch vencido")
    void clutchVencido() {
        Round r = round(1, Team.CT); // CT vence
        // Meus 4 aliados morrem; eu fico sozinho.
        for (int i = 0; i < 4; i++) {
            r.addEvent(kill(inimigo1, jogador(10L + i, "ALIADO" + i), Team.TR, Team.CT, 1000 + i * 100));
        }
        // Eu mato os 5 inimigos.
        for (int i = 0; i < 5; i++) {
            r.addEvent(kill(eu, jogador(20L + i, "INIMIGO" + i), Team.CT, Team.TR, 2000 + i * 100));
        }

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(1.0, m.get("clutchesAttempted"));
        assertEquals(1.0, m.get("clutchesWon"));
    }

    @Test
    @DisplayName("ficar sozinho e morrer conta como tentativa, não vitória")
    void clutchPerdido() {
        Round r = round(1, Team.TR); // TR vence; eu sou CT
        for (int i = 0; i < 4; i++) {
            r.addEvent(kill(inimigo1, jogador(10L + i, "ALIADO" + i), Team.TR, Team.CT, 1000 + i * 100));
        }
        r.addEvent(kill(inimigo1, eu, Team.TR, Team.CT, 3000));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(1.0, m.get("clutchesAttempted"));
        assertEquals(0.0, m.get("clutchesWon"));
    }

    @Test
    @DisplayName("round normal, sem ficar sozinho, não gera clutch")
    void semClutch() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 1000));

        var m = calcular(partida(1, r)).getMetrics();
        assertEquals(0.0, m.get("clutchesAttempted"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  ROBUSTEZ
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("eventos com tick nulo não quebram o cálculo")
    void tickNuloNaoQuebra() {
        Round r = round(1, Team.CT);
        MatchEvent semTick = kill(eu, inimigo1, Team.CT, Team.TR, 1000);
        semTick.setTick(null);
        r.addEvent(semTick);

        assertDoesNotThrow(() -> calcular(partida(1, r)));
    }

    @Test
    @DisplayName("tickRate ausente assume 64 em vez de dividir por zero")
    void tickRateAusente() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(inimigo1, aliado, Team.TR, Team.CT, 1000));
        r.addEvent(kill(eu, inimigo1, Team.CT, Team.TR, 1000 + 2 * 64));

        Match match = partida(1, r);
        match.setTickRate(null);

        var m = calcular(match).getMetrics();
        assertEquals(1.0, m.get("tradeKills"), "sem tickRate, assume 64 e o trade é detectado");
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private PlayerStatResult calcular(Match match) {
        return strategy.calculate(match, eu);
    }

    private Player jogador(Long id, String nome) {
        return Player.builder().id(id).steamId64("7656119900000000" + id).displayName(nome).build();
    }

    private Match partida(int totalRounds, Round... rounds) {
        Match m = Match.builder()
                .id(1L)
                .mapName("de_teste")
                .totalRounds(totalRounds)
                .tickRate(TICK_RATE)
                .rounds(new ArrayList<>())
                .build();
        for (Round r : rounds) m.addRound(r);
        return m;
    }

    private Round round(int numero, Team vencedor) {
        return Round.builder()
                .id((long) numero)
                .roundNumber(numero)
                .winnerSide(vencedor)
                .events(new ArrayList<>())
                .build();
    }

    private MatchEvent kill(Player ator, Player vitima, Team ladoAtor, Team ladoVitima, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.KILL)
                .actor(ator).actorSide(ladoAtor)
                .victim(vitima).victimSide(ladoVitima)
                .tick(tick)
                .build();
    }

    private MatchEvent dano(Player ator, Player vitima, int quantidade, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.DAMAGE)
                .actor(ator).actorSide(Team.CT)
                .victim(vitima).victimSide(Team.TR)
                .damageAmount(quantidade)
                .tick(tick)
                .build();
    }
}
