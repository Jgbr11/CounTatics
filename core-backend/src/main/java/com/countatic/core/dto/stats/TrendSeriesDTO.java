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

    /**
     * Média da faixa de rank do jogador, quando há amostra suficiente.
     *
     * <p>É a segunda referência do gráfico: a média própria diz se ele está
     * melhorando em relação a si mesmo; esta diz se já está acima do esperado
     * para o nível. Vem {@code null} enquanto a faixa não tiver o mínimo de
     * amostras — um número calculado sobre cinco partidas pareceria preciso
     * sem ser.</p>
     */
    private Double mediaDaFaixa;

    /** Rótulo da faixa usada em {@link #mediaDaFaixa}, ex: "Azul (10.000–14.999)". */
    private String faixaLabel;

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
         * Se o time do jogador venceu. {@code null} quando desconhecido —
         * partida analisada antes de o resultado passar a ser guardado, ou
         * empate.
         */
        private Boolean won;

        /** Rounds do lado do jogador, já orientado — não é o placar CT/TR cru. */
        private Integer scoreSelf;

        /** Rounds do adversário. */
        private Integer scoreEnemy;

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
