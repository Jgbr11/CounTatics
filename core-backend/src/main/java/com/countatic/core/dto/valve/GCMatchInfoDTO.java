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
        /**
         * Timestamp unix de INÍCIO da partida (campo {@code matchtime} do GC).
         * Usado para preencher {@code Match.playedAt} com a hora real da partida
         * em vez da hora em que a demo foi parseada.
         */
        private long matchTimeUnix;
        /** Duração real em segundos ({@code match_duration} das round stats). */
        private int matchDuration;
        private String mapName;
        private String matchResult;
        /** Placar já orientado pelo time de quem solicitou. */
        private int roundsWon;
        private int roundsLost;
        /** Placar bruto por índice de time, sem orientação. */
        private List<Integer> teamScores;
        /**
         * CS Rating (Premier) de quem solicitou, consultado no perfil do GC.
         * Define a faixa de comparação da partida.
         */
        private Integer requesterRank;
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
        /** "A" ou "B" — CT/TR não é recuperável para a partida toda (lados trocam). */
        private String team;
        private int teamIndex;
    }
}
