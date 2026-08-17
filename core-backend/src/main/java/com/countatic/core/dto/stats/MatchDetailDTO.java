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

    /** CS Rating de quem cadastrou a partida — define a faixa de comparação. */
    private Integer csRating;
    /** Faixa de comparação (nome da constante). */
    private String rankTier;
    /** Rótulo legível da faixa, ex: "Azul (10.000–14.999)". */
    private String rankTierLabel;

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

        /**
         * Dicas de melhoria por categoria: {@code {"Aim": {"kdRatio": Insight, ...}, ...}}.
         *
         * <p>Cada {@link Insight} carrega o texto e a gravidade, para a página
         * poder ordenar por urgência e escolher o ícone sem tentar adivinhar
         * pelo texto.</p>
         */
        private Map<String, Map<String, Insight>> insights;

        /**
         * Comparação com jogadores da mesma faixa.
         *
         * <p>Preenchido apenas para o jogador dono da partida, e apenas quando
         * há amostra suficiente. Caso contrário traz o motivo em {@code aviso}
         * — mostrar percentil calculado sobre poucas amostras seria pior do que
         * não mostrar nada.</p>
         */
        private Object baseline;
    }
}
