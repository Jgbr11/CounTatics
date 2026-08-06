package com.countatic.core.service;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.countatic.core.dto.parser.ParsedEventDTO;
import com.countatic.core.dto.parser.ParsedRoundDTO;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MatchAnalysisServiceTest {

    @Autowired
    private MatchAnalysisService matchAnalysisService;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRepository playerRepository;

    @Test
    @DisplayName("Deve processar DTO da demo, converter em entidades, salvar no H2 e executar Strategies")
    void shouldProcessDemoConvertAndRunStrategies() {
        // Arrange
        String demoFileName = "test_match.dem";
        String demoHash = "sha256_mock_hash_123456";

        ParsedEventDTO killEvent = ParsedEventDTO.builder()
                .eventType(EventType.KILL)
                .tick(100)
                .actorSteamId("76561198012345678")
                .actorName("Fallen")
                .actorSide(Team.CT)
                .victimSteamId("76561198087654321")
                .victimName("Simple")
                .victimSide(Team.TR)
                .weapon("awp")
                .isHeadshot(true)
                .build();

        ParsedRoundDTO round1 = ParsedRoundDTO.builder()
                .roundNumber(1)
                .winnerSide(Team.CT)
                .endReason(RoundEndReason.CT_WIN_ELIMINATION)
                .startTick(0)
                .endTick(200)
                .durationSeconds(12.5)
                .ctScoreAfter(1)
                .trScoreAfter(0)
                .events(List.of(killEvent))
                .build();

        ParsedDemoDTO parsedDemo = ParsedDemoDTO.builder()
                .mapName("de_mirage")
                .serverName("Valve Matchmaking")
                .durationSeconds(120)
                .scoreCT(13)
                .scoreTR(11)
                .totalRounds(24)
                .tickRate(64)
                .playedAt(Instant.now())
                .rounds(List.of(round1))
                .build();

        // Act
        MatchAnalysisResult result = matchAnalysisService.processDemo(demoFileName, demoHash, parsedDemo);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getMapName()).isEqualTo("de_mirage");
        assertThat(result.getFinalScore()).isEqualTo("13-11");
        assertThat(result.getPlayerStats()).isNotEmpty();

        // Verificar se foi persistido no banco H2
        assertThat(matchRepository.existsByDemoFileHash(demoHash)).isTrue();
        assertThat(playerRepository.existsBySteamId64("76561198012345678")).isTrue();

        Player fallen = playerRepository.findBySteamId64("76561198012345678").orElse(null);
        assertThat(fallen).isNotNull();
        assertThat(fallen.getDisplayName()).isEqualTo("Fallen");
    }
}
