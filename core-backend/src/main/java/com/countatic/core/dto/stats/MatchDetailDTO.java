package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Visão completa de uma partida, consumida pela página de detalhes
 * ({@code /m/{token}}) e pelo endpoint {@code GET /api/matches/{id}}.
 *
 * <p>Existe para não expor as entidades JPA diretamente: a árvore
 * {@code Match → Round → MatchEvent} tem dezenas de milhares de nós e
 * referências bidirecionais que serializariam em loop.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchDetailDTO {

    private Long matchId;
    private String publicToken;
    private String mapName;
    private Integer scoreCT;
    private Integer scoreTR;
    private Integer totalRounds;
    private Integer durationSeconds;
    private Integer tickRate;
    private Instant playedAt;
    private String status;

    /** Uma linha por jogador, com as métricas agregadas de todas as categorias. */
    private List<PlayerRow> players;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlayerRow {
        private String steamId64;
        private String playerName;

        /** Contagens básicas derivadas dos eventos. */
        private int kills;
        private int deaths;
        private int assists;
        private int headshots;
        private int damage;

        /** Métricas por categoria: {@code {"Aim": {"kdRatio": 1.2, ...}, ...}}. */
        private Map<String, Map<String, Double>> metrics;

        /** Dicas de melhoria por categoria. */
        private Map<String, Map<String, String>> insights;
    }
}
