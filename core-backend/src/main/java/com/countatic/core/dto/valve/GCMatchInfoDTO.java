package com.countatic.core.dto.valve;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO que mapeia a resposta do endpoint POST /match-info do Steam Bot,
 * contendo informações da partida obtidas via Game Coordinator do CS2.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GCMatchInfoDTO {

    private boolean success;
    private MatchInfo matchInfo;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MatchInfo {
        private String matchId;
        private int matchDuration;
        private String mapName;
        private String matchResult;
        private int roundsWon;
        private int roundsLost;
        private String demoUrl;
        private List<PlayerStats> players;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlayerStats {
        private String steamId64;
        private String playerName;
        private int kills;
        private int deaths;
        private int assists;
        private int headshots;
        private int mvps;
        private int score;
        private String team;
    }
}
