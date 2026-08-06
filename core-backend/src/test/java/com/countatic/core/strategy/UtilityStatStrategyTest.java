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

        // 2 flashes lançadas
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN)
                .actor(testPlayer)
                .build());
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_THROWN)
                .actor(testPlayer)
                .build());

        // 1 flash em inimigo (duração 3s)
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_BLINDED)
                .actor(testPlayer)
                .isEnemyFlash(true)
                .flashDurationSeconds(3.0)
                .build());

        // 1 flash em aliado (duração 1.5s)
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.FLASH_BLINDED)
                .actor(testPlayer)
                .isEnemyFlash(false)
                .flashDurationSeconds(1.5)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = utilityStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getCategory()).isEqualTo("Utility");
        assertThat(result.getMetrics().get("totalFlashesThrown")).isEqualTo(2.0);
        assertThat(result.getMetrics().get("totalEnemyBlinds")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("totalTeamBlinds")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("flashEfficiency")).isEqualTo(50.0); // 1 enemy blind / 2 flashes thrown
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
}
