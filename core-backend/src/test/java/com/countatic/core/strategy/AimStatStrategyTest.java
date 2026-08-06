package com.countatic.core.strategy;

import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.impl.AimStatStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AimStatStrategyTest {

    private AimStatStrategy aimStatStrategy;
    private Player testPlayer;
    private Match testMatch;

    @BeforeEach
    void setUp() {
        aimStatStrategy = new AimStatStrategy();

        testPlayer = Player.builder()
                .id(1L)
                .steamId64("76561198012345678")
                .displayName("Fallen")
                .build();

        testMatch = Match.builder()
                .id(100L)
                .mapName("de_mirage")
                .totalRounds(10)
                .scoreCT(6)
                .scoreTR(4)
                .build();
    }

    @Test
    @DisplayName("Deve calcular 50% de Headshot quando metade das kills forem headshot")
    void shouldCalculateFiftyPercentHeadshotRate() {
        Round round1 = Round.builder().id(1L).roundNumber(1).build();

        // Kill 1: Headshot
        MatchEvent kill1 = MatchEvent.builder()
                .eventType(EventType.KILL)
                .actor(testPlayer)
                .isHeadshot(true)
                .build();

        // Kill 2: Não-Headshot
        MatchEvent kill2 = MatchEvent.builder()
                .eventType(EventType.KILL)
                .actor(testPlayer)
                .isHeadshot(false)
                .build();

        round1.addEvent(kill1);
        round1.addEvent(kill2);
        testMatch.addRound(round1);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result).isNotNull();
        assertThat(result.getCategory()).isEqualTo("Aim");
        assertThat(result.getSteamId64()).isEqualTo("76561198012345678");

        Double hsPct = result.getMetrics().get("headshotPercentage");
        assertThat(hsPct).isEqualTo(50.0);

        assertThat(result.getMetrics().get("totalKills")).isEqualTo(2.0);
        assertThat(result.getMetrics().get("totalHeadshotKills")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("killsPerRound")).isEqualTo(0.2); // 2 kills / 10 rounds
    }

    @Test
    @DisplayName("Deve calcular K/D Ratio corretamente")
    void shouldCalculateKdRatioCorrectly() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // 3 kills do jogador
        for (int i = 0; i < 3; i++) {
            round.addEvent(MatchEvent.builder()
                    .eventType(EventType.KILL)
                    .actor(testPlayer)
                    .isHeadshot(true)
                    .build());
        }

        // 1 morte do jogador
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.KILL)
                .victim(testPlayer)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("kdRatio")).isEqualTo(3.0);
        assertThat(result.getMetrics().get("totalKills")).isEqualTo(3.0);
        assertThat(result.getMetrics().get("totalDeaths")).isEqualTo(1.0);
        assertThat(result.getInsights().get("kdRatio")).contains("K/D excelente");
    }

    @Test
    @DisplayName("Deve calcular Crosshair Placement Score baseado em disparos no nível da cabeça")
    void shouldCalculateCrosshairPlacementScore() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // Disparo 1: Ângulo Y = 2.0° (Dentro do threshold <= 5.0°)
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .viewAngleY(2.0)
                .build());

        // Disparo 2: Ângulo Y = -15.0° (Fora do threshold)
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .viewAngleY(-15.0)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        Double crosshairScore = result.getMetrics().get("crosshairPlacementScore");
        assertThat(crosshairScore).isEqualTo(50.0); // 1 de 2 disparos válidos = 50%
    }
}
