package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Os melhores rounds do jogador numa partida.
 *
 * <p>É a pergunta que o placar não responde: "24 kills" não conta que quatro
 * delas foram no mesmo round, sozinho contra três. O agregado dilui exatamente
 * o momento de que a pessoa lembra.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoundHighlightsDTO {

    private Long matchId;
    private String steamId64;

    /** Do melhor para o pior, no máximo três. Vazio quando nenhum round se destacou. */
    private List<Destaque> destaques;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Destaque {

        private int roundNumber;

        /**
         * Pontuação do round.
         *
         * <p>Exposta de propósito: sem ela, "por que este round e não aquele"
         * não teria resposta na tela. A unidade é a kill — 1.0 é uma kill.</p>
         */
        private double pontuacao;

        /** Rótulo curto, ex.: {@code "Clutch 1v3"} ou {@code "4 kills"}. */
        private String titulo;

        /** Frase inteira, ex.: {@code "Quatro kills e o round fechado sozinho contra três."} */
        private String descricao;

        private int kills;
        private int headshots;
        private int damage;
        private int tradeKills;

        /** Inimigos vivos quando ficou sozinho; 0 quando não houve clutch. */
        private int clutchContra;
        private boolean clutchVencido;

        /** Venceu o primeiro duelo do round. */
        private boolean abertura;

        private boolean plantou;
        private boolean desarmou;

        private boolean venceuRound;
    }
}
