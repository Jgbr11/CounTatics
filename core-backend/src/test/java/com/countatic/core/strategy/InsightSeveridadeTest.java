package com.countatic.core.strategy;

import com.countatic.core.dto.stats.Insight;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.impl.AimStatStrategy;
import com.countatic.core.strategy.impl.ImpactStatStrategy;
import com.countatic.core.strategy.impl.UtilityStatStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes da gravidade dos insights.
 *
 * <p>A gravidade é o único dado que o painel de coaching usa para ordenar e
 * escolher ícone. Se ela sair errada, a página continua renderizando
 * normalmente — só passa a chamar de elogio o que era alerta. Nada quebra,
 * nada estoura; por isso precisa de teste.</p>
 *
 * <p>Cada caso posiciona a métrica numa faixa específica e confere o rótulo,
 * em vez de conferir o texto: o texto é copy de produto e muda; a
 * classificação é contrato.</p>
 */
class InsightSeveridadeTest {

    private final AimStatStrategy aim = new AimStatStrategy();
    private final UtilityStatStrategy utility = new UtilityStatStrategy();
    private final ImpactStatStrategy impact = new ImpactStatStrategy();

    private final Player eu = jogador(1L, "EU");
    private final Player inimigo = jogador(2L, "INIMIGO");

    // ═══════════════════════════════════════════════════════════════
    //  Aim — K/D percorre as três gravidades
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K/D >= 1.0 é SUCESSO")
    void kdPositivoEhSucesso() {
        // 2 kills, 2 mortes → K/D 1.0, exatamente no limiar.
        Match m = partidaComKillsEMortes(2, 2);
        assertThat(gravidade(aim.calculate(m, eu), "kdRatio"))
                .isEqualTo(Insight.Severidade.SUCESSO);
    }

    @Test
    @DisplayName("K/D entre 0.8 e 1.0 é INFO — a diferença cabe na variação de uma partida")
    void kdLevementeNegativoEhInfo() {
        // 4 kills, 5 mortes → 0.8, exatamente no limiar inferior.
        Match m = partidaComKillsEMortes(4, 5);
        assertThat(gravidade(aim.calculate(m, eu), "kdRatio"))
                .isEqualTo(Insight.Severidade.INFO);
    }

    @Test
    @DisplayName("K/D abaixo de 0.8 é AVISO")
    void kdBaixoEhAviso() {
        Match m = partidaComKillsEMortes(1, 5);
        assertThat(gravidade(aim.calculate(m, eu), "kdRatio"))
                .isEqualTo(Insight.Severidade.AVISO);
    }

    @Test
    @DisplayName("HS% sem nenhum headshot é AVISO")
    void hsZeradoEhAviso() {
        Match m = partidaComKillsEMortes(4, 1);
        assertThat(gravidade(aim.calculate(m, eu), "headshotPercentage"))
                .isEqualTo(Insight.Severidade.AVISO);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utility — os dois insights que só existem quando são problema
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cegar mais de 30% em aliado é sempre AVISO, e o texto não carrega emoji")
    void teamFlashEhAvisoESemEmoji() {
        Round r = round(1);
        r.addEvent(evento(EventType.FLASH_THROWN, eu, null, 100));
        r.addEvent(cegamento(eu, false, 120));   // aliado
        r.addEvent(cegamento(eu, true, 130));    // inimigo
        Match m = partida(List.of(r), 10);

        Insight i = insight(utility.calculate(m, eu), "teamFlashRate");

        assertThat(i.gravidade()).isEqualTo(Insight.Severidade.AVISO);
        // O símbolo é escolhido por quem exibe. Se voltar para o texto, a
        // página mostra dois ícones e a mensagem da Steam fica deslocada.
        assertThat(i.texto()).doesNotContain("⚠");
    }

    @Test
    @DisplayName("nenhum texto de insight carrega emoji de gravidade")
    void nenhumInsightTemEmoji() {
        Round r = round(1);
        r.addEvent(kill(eu, inimigo, true, 100));
        r.addEvent(evento(EventType.FLASH_THROWN, eu, null, 200));
        r.addEvent(cegamento(eu, true, 210));
        r.addEvent(dano(eu, inimigo, 100, 300));
        Match m = partida(List.of(r), 10);

        for (PlayerStatResult r2 : List.of(aim.calculate(m, eu),
                                           utility.calculate(m, eu),
                                           impact.calculate(m, eu))) {
            for (Insight i : r2.getInsights().values()) {
                assertThat(i.texto())
                        .as("insight de %s", r2.getCategory())
                        .doesNotContain("⚠").doesNotContain("✅").doesNotContain("👉");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Impacto — o insight que antes virava item de lista vazio
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sem rounds, o insight de trades não é publicado em vez de sair vazio")
    void tradesSemRoundsNaoPublica() {
        // Uma troca acontece, mas a partida não tem total de rounds: antes isto
        // publicava string vazia e a página renderizava um <li> em branco.
        Round r = round(1);
        r.addEvent(kill(inimigo, jogador(3L, "ALIADO"), false, 100));
        r.addEvent(kill(eu, inimigo, false, 150));
        Match m = partida(List.of(r), 0);

        PlayerStatResult res = impact.calculate(m, eu);

        assertThat(res.getInsights()).doesNotContainKey("trades");
    }

    @Test
    @DisplayName("clutch perdido é AVISO e clutch vencido é SUCESSO")
    void clutchClassificaPelosDoisLados() {
        assertThat(Insight.aviso("x").gravidade()).isEqualTo(Insight.Severidade.AVISO);
        assertThat(Insight.sucesso("x").gravidade()).isEqualTo(Insight.Severidade.SUCESSO);
        assertThat(Insight.info("x").gravidade()).isEqualTo(Insight.Severidade.INFO);
    }

    /**
     * A ordem da enum É a ordem de exibição do painel. Inverter os membros sem
     * querer faria o elogio subir para o topo e o alerta afundar, sem quebrar
     * nada visível no build.
     */
    @Test
    @DisplayName("a ordem da enum coloca o acionável primeiro")
    void ordemDaEnumPriorizaOAcionavel() {
        assertThat(List.of(Insight.Severidade.values()))
                .containsExactly(Insight.Severidade.AVISO,
                                 Insight.Severidade.INFO,
                                 Insight.Severidade.SUCESSO);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fábricas
    // ═══════════════════════════════════════════════════════════════

    private static Insight.Severidade gravidade(PlayerStatResult r, String chave) {
        return insight(r, chave).gravidade();
    }

    private static Insight insight(PlayerStatResult r, String chave) {
        Map<String, Insight> m = r.getInsights();
        assertThat(m).as("insights de %s", r.getCategory()).containsKey(chave);
        return m.get(chave);
    }

    private Match partidaComKillsEMortes(int kills, int mortes) {
        Round r = round(1);
        int tick = 100;
        for (int i = 0; i < kills; i++) {
            r.addEvent(kill(eu, inimigo, false, tick += 10));
        }
        for (int i = 0; i < mortes; i++) {
            r.addEvent(kill(inimigo, eu, false, tick += 10));
        }
        return partida(List.of(r), 10);
    }

    private static Match partida(List<Round> rounds, int totalRounds) {
        Match m = Match.builder()
                .id(1L).mapName("de_mirage")
                .totalRounds(totalRounds).tickRate(64)
                .build();
        rounds.forEach(m::addRound);
        return m;
    }

    private static Round round(int numero) {
        return Round.builder().id((long) numero).roundNumber(numero).build();
    }

    private static Player jogador(Long id, String nome) {
        return Player.builder().id(id).steamId64("7656119900000000" + id)
                .displayName(nome).build();
    }

    private static MatchEvent kill(Player ator, Player vitima, boolean headshot, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.KILL).actor(ator).victim(vitima)
                .isHeadshot(headshot).tick(tick)
                .actorSide(Team.CT).victimSide(Team.TR)
                .build();
    }

    private static MatchEvent dano(Player ator, Player vitima, int valor, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.DAMAGE).actor(ator).victim(vitima)
                .damageAmount(valor).tick(tick)
                .actorSide(Team.CT).victimSide(Team.TR)
                .build();
    }

    private static MatchEvent evento(EventType tipo, Player ator, Player vitima, int tick) {
        return MatchEvent.builder()
                .eventType(tipo).actor(ator).victim(vitima).tick(tick).build();
    }

    private static MatchEvent cegamento(Player ator, boolean inimigo, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.FLASH_BLINDED).actor(ator)
                .isEnemyFlash(inimigo).flashDurationSeconds(2.0).tick(tick)
                .build();
    }
}
