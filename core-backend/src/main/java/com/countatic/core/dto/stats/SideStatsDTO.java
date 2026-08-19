package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Desempenho de um jogador separado por lado numa partida.
 *
 * <p>Responde a pergunta que a média da partida esconde: no CS a postura muda
 * por completo entre CT e TR, e uma eficiência de flash de 55% pode ser 75% de
 * um lado e 30% do outro — situações com treinos opostos.</p>
 *
 * <p>Não vai no payload da página. É buscado sob demanda, quando o jogador
 * alterna o lado: calcular os dois lados dos dez jogadores em toda visita
 * triplicaria o trabalho para uma informação que quase sempre não é aberta.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SideStatsDTO {

    private Long matchId;
    private String steamId64;
    private String playerName;

    private Lado ct;
    private Lado tr;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Lado {

        /**
         * Rounds em que o jogador atuou por este lado.
         *
         * <p>É o denominador de toda métrica por round deste recorte — e o
         * motivo de os números de um lado não serem metade dos da partida.</p>
         */
        private int roundsJogados;

        /** Mesmo formato do detalhe da partida: categoria → métrica → valor. */
        private Map<String, Map<String, Double>> metrics;

        private Map<String, Map<String, Insight>> insights;
    }
}
