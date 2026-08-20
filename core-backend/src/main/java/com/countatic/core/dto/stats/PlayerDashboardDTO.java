package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Visão consolidada de um jogador nas últimas partidas.
 *
 * <p>É o oposto do {@link MatchDetailDTO}: lá o eixo é <i>uma partida com
 * vários jogadores</i>; aqui é <i>um jogador ao longo de várias partidas</i>.
 * A página da partida responde "como eu fui nesse jogo"; esta responde "como
 * eu estou".</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerDashboardDTO {

    private String steamId64;
    private String playerName;

    /** Quantas partidas entraram nas médias. */
    private int partidasAnalisadas;

    /** Faixa e rating da partida mais recente — é o que representa o jogador hoje. */
    private String rankTier;
    private String rankTierLabel;
    private Integer csRating;

    private int vitorias;
    private int derrotas;

    /**
     * Partidas cujo resultado não foi registrado.
     *
     * <p>Exposto em vez de somado a alguma das duas: as partidas analisadas
     * antes de o resultado passar a ser guardado ficariam contadas como derrota
     * se caíssem no lado errado, e o jogador leria um retrospecto falso.</p>
     */
    private int resultadoDesconhecido;

    /**
     * Média de cada métrica no período, ex: {@code {"adr": 84.2, "kdRatio": 1.13}}.
     *
     * <p>Só métricas medidas entram na média — a ausente é ignorada, não
     * tratada como zero. É a mesma regra que vale no cálculo por partida.</p>
     */
    private Map<String, Double> medias;

    /** Média da faixa para as mesmas métricas, quando há amostra. */
    private Map<String, Double> mediasDaFaixa;

    /**
     * Desempenho agregado por mapa, do mais jogado para o menos.
     *
     * <p>Cobre a mesma janela das médias — não o histórico inteiro. Misturar
     * as duas escalas na mesma tela faria "ADR 84" e "ADR na Mirage 91"
     * parecerem contraditórios quando na verdade contam períodos
     * diferentes.</p>
     */
    private List<MapaResumo> porMapa;

    private List<PartidaResumo> partidas;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MapaResumo {
        private String mapName;
        private int partidas;

        private int vitorias;
        private int derrotas;

        /**
         * Partidas sem resultado registrado.
         *
         * <p>Exposto em vez de somado a um dos lados, pela mesma razão do
         * retrospecto geral: as partidas analisadas antes de o resultado
         * passar a ser guardado dariam um saldo falso.</p>
         */
        private int resultadoDesconhecido;

        /** Médias no mapa. {@code null} quando a métrica não foi medida em nenhuma. */
        private Double kdRatio;
        private Double adr;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PartidaResumo {
        private Long matchId;

        /** Token da URL de detalhes, {@code /m/{publicToken}}. */
        private String publicToken;

        private String mapName;
        private Instant playedAt;

        private Boolean won;
        private Integer scoreSelf;
        private Integer scoreEnemy;

        private Integer kills;
        private Integer deaths;
        private Double adr;
        private Double kdRatio;
    }
}
