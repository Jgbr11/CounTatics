package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Desempenho de um jogador por arma numa partida.
 *
 * <p>Responde a pergunta que a média esconde: 50% de headshot pode ser 70% de
 * AK e 20% de AWP — armas com treino, posicionamento e função diferentes.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeaponStatsDTO {

    private Long matchId;
    private String steamId64;

    /** Ordenadas por kills, decrescente — a arma que mais matou vem primeiro. */
    private List<Arma> armas;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Arma {

        /** Identificador da Valve, ex: {@code "ak47"}. Estável para o CSS e para os rótulos. */
        private String id;

        private int kills;
        private int headshots;

        /** Percentual de kills na cabeça. {@code null} sem nenhuma kill. */
        private Double headshotPercentage;

        private int damage;

        /**
         * Disparos registrados.
         *
         * <p>Só existe para armas de mira — o parser não emite disparo de
         * granada, faca ou zeus. Zero significa que a arma matou sem que
         * houvesse disparo medido (kill de faca, por exemplo).</p>
         */
        private int tiros;

        /** Disparos que causaram dano. Ver {@code WeaponStatsService}. */
        private int acertos;

        /**
         * Taxa de acerto.
         *
         * <p>{@code null} quando não há disparos suficientes para o número
         * significar algo — como em toda taxa deste sistema, amostra pequena
         * não vira estatística.</p>
         */
        private Double accuracy;
    }
}
