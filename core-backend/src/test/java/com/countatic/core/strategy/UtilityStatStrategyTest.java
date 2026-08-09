package com.countatic.core.strategy;

import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.impl.UtilityStatStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtilityStatStrategyTest {

    private UtilityStatStrategy utilityStatStrategy;
    private Player testPlayer;
    private Match testMatch;

    @BeforeEach
    void setUp() {
        utilityStatStrategy = new UtilityStatStrategy();

        testPlayer = Player.builder()
                .id(1L)
                .steamId64("76561198012345678")
                .displayName("Coldzera")
                .build();

        testMatch = Match.builder()
                .id(200L)
                .mapName("de_dust2")
                .totalRounds(10)
                .build();
    }

    @Test
    @DisplayName("Deve calcular Flash Efficiency e Team Flash Rate corretamente")
    void shouldCalculateFlashEfficiencyAndTeamFlashRate() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // 2 flashes lançadas, em instantes distintos.
        // Os ticks importam: a eficiência agora atribui cada cegamento à flash
        // que o causou, em vez de somar cegamentos soltos.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN)
                .actor(testPlayer)
                .tick(1000)
                .build());
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN)
                .actor(testPlayer)
                .tick(5000)
                .build());

        // 1 inimigo cegado pela PRIMEIRA flash (duração 3s)
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_BLINDED)
                .actor(testPlayer)
                .tick(1002)
                .isEnemyFlash(true)
                .flashDurationSeconds(3.0)
                .build());

        // 1 aliado cegado pela SEGUNDA flash (duração 1.5s)
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_BLINDED)
                .actor(testPlayer)
                .tick(5002)
                .isEnemyFlash(false)
                .flashDurationSeconds(1.5)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = utilityStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getCategory()).isEqualTo("Utility");
        assertThat(result.getMetrics().get("totalFlashesThrown")).isEqualTo(2.0);
        assertThat(result.getMetrics().get("totalEnemyBlinds")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("totalTeamBlinds")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("flashEfficiency")).isEqualTo(50.0); // 1 de 2 flashes cegou inimigo
        assertThat(result.getMetrics().get("teamFlashRate")).isEqualTo(50.0); // 1 team blind / 2 total blinds
        assertThat(result.getMetrics().get("avgEnemyFlashDuration")).isEqualTo(3.0);
        assertThat(result.getInsights().get("teamFlashRate")).contains("cegaram aliados");
    }

    @Test
    @DisplayName("Deve calcular dano de utilitária por round")
    void shouldCalculateUtilityDamagePerRound() {
        Round round1 = Round.builder().id(1L).roundNumber(1).build();

        // Dano de HE (50 HP)
        round1.addEvent(MatchEvent.builder()
                .eventType(EventType.DAMAGE)
                .actor(testPlayer)
                .weapon("hegrenade")
                .damageAmount(50)
                .build());

        // Dano de Molotov (30 HP)
        round1.addEvent(MatchEvent.builder()
                .eventType(EventType.DAMAGE)
                .actor(testPlayer)
                .weapon("inferno")
                .damageAmount(30)
                .build());

        testMatch.addRound(round1);

        PlayerStatResult result = utilityStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("totalUtilityDamage")).isEqualTo(80.0);
        assertThat(result.getMetrics().get("utilityDamagePerRound")).isEqualTo(8.0); // 80 dmg / 10 total rounds
    }

    /**
     * REGRESSÃO — a eficiência de flash não pode passar de 100%.
     *
     * <p>A fórmula anterior dividia CEGAMENTOS por FLASHES: uma única flash
     * pegando 3 inimigos rendia 300%, e o número deixava de ser porcentagem.
     * Aqui uma flash cega 3 inimigos — o resultado correto é 100%.</p>
     */
    @Test
    @DisplayName("uma flash cegando 3 inimigos dá 100%, não 300%")
    void flashEfficiencyNaoPassaDeCem() {
        Round round = Round.builder().id(9L).roundNumber(1).build();

        // Uma única flash, estourando no tick 1000.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN)
                .actor(testPlayer).tick(1000)
                .build());

        // Três inimigos cegados por ela, praticamente no mesmo instante.
        for (int i = 0; i < 3; i++) {
            round.addEvent(MatchEvent.builder()
                    .eventType(EventType.FLASH_BLINDED)
                    .actor(testPlayer).tick(1000 + i)
                    .isEnemyFlash(true).flashDurationSeconds(2.5)
                    .build());
        }

        testMatch.addRound(round);
        PlayerStatResult result = utilityStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("flashEfficiency")).isEqualTo(100.0);
        // A razão cegamentos-por-flash passa de 1 legitimamente: é ela que
        // mede a flash bem colocada, que pega o time inteiro.
        assertThat(result.getMetrics().get("enemyBlindsPerFlash")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("flash que não cega ninguém derruba a eficiência")
    void flashSemCegamentoContaComoIneficaz() {
        Round round = Round.builder().id(10L).roundNumber(1).build();

        // Duas flashes; só a primeira cega alguém.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN).actor(testPlayer).tick(1000).build());
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN).actor(testPlayer).tick(5000).build());
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_BLINDED).actor(testPlayer).tick(1002)
                .isEnemyFlash(true).flashDurationSeconds(2.0).build());

        testMatch.addRound(round);
        PlayerStatResult result = utilityStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("flashEfficiency")).isEqualTo(50.0);
    }

    /**
     * O cegamento precisa ser atribuído à flash CERTA. Sem a janela de tempo,
     * qualquer cegamento validaria qualquer flash da partida.
     */
    @Test
    @DisplayName("cegamento distante no tempo não é atribuído à flash")
    void cegamentoForaDaJanelaNaoConta() {
        Round round = Round.builder().id(11L).roundNumber(1).build();

        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN).actor(testPlayer).tick(1000).build());
        // 10 segundos depois (640 ticks a 64 tick): não pode ser desta flash.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_BLINDED).actor(testPlayer).tick(1000 + 640)
                .isEnemyFlash(true).flashDurationSeconds(2.0).build());

        testMatch.addRound(round);
        PlayerStatResult result = utilityStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("flashEfficiency")).isEqualTo(0.0);
    }
}
