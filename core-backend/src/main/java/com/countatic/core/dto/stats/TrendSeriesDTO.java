package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Evolução de uma métrica de um jogador ao longo das últimas partidas.
 *
 * <p>Vem de {@code player_match_stats}, que já guarda o desempenho calculado
 * por partida. O caminho alternativo — recalcular pelas Strategies, como a
 * página da partida faz — carregaria os eventos de dez partidas numa única
 * requisição, e é exatamente para evitar isso que aquela tabela existe.</p>
 *
 * <p>Os pontos vêm em <b>ordem cronológica</b> (mais antigo primeiro), que é a
 * ordem em que um gráfico de linha é lido. A consulta ordena ao contrário para
 * poder limitar aos N mais recentes; a inversão acontece no serviço.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendSeriesDTO {

    private String steamId64;

    /** Chave da métrica, ex: {@code "adr"}. */
    private String metric;

    /** Rótulo legível, reaproveitado do {@code BaselineService}. */
    private String label;

    /**
     * Se valores maiores são melhores.
     *
     * <p>Vai junto porque quem desenha precisa saber para que lado a linha
     * subindo é boa notícia — em {@code deathsPerRound}, subir é piorar.</p>
     */
    private boolean maiorEhMelhor;

    /** Média da própria série, para a linha de referência do gráfico. */
    private Double media;

    private List<Ponto> pontos;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Ponto {
        private Long matchId;
        private String mapName;

        /** Quando a partida foi <b>jogada</b> — não quando foi analisada. */
        private Instant playedAt;

        /**
         * Valor da métrica naquela partida.
         *
         * <p>Pode ser {@code null}: a métrica é omitida quando não há
         * denominador (quem não lançou flash não tem eficiência de flash). O
         * gráfico precisa saber disso para interromper a linha em vez de
         * desenhar um mergulho até zero que nunca aconteceu.</p>
         */
        private Double valor;
    }
}
