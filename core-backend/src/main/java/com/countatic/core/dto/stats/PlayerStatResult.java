package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Resultado do cálculo de uma Strategy de estatísticas para um jogador
 * específico em uma partida.
 *
 * <p>Cada Strategy produz um {@code PlayerStatResult} contendo:</p>
 * <ul>
 *   <li>{@code category} — Nome da categoria de métricas (ex: "Aim", "Utility")</li>
 *   <li>{@code metrics} — Mapa chave-valor com as métricas calculadas</li>
 *   <li>{@code insights} — Mapa de insights/dicas geradas a partir das métricas</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerStatResult {

    /** Categoria da métrica (ex: "Aim", "Utility", "Positioning"). */
    private String category;

    /** SteamID64 do jogador analisado. */
    private String steamId64;

    /** Nome de exibição do jogador. */
    private String playerName;

    /**
     * Métricas calculadas: chave é o nome da métrica, valor é o resultado numérico.
     * Exemplos:
     * <ul>
     *   <li>"headshotPercentage" → 48.5</li>
     *   <li>"crosshairPlacementScore" → 72.3</li>
     *   <li>"flashEfficiency" → 65.0</li>
     *   <li>"utilityDamagePerRound" → 12.7</li>
     * </ul>
     */
    private Map<String, Double> metrics;

    /**
     * Insights gerados a partir das métricas.
     *
     * <p>Chave é o identificador do insight — em geral o nome da métrica que o
     * originou, mas nem sempre ({@code "utilityDamage"} vem de
     * {@code utilityDamagePerRound}, e {@code "trades"}, {@code "clutches"} e
     * {@code "smokeUsage"} não têm métrica 1:1). O valor traz o texto e a
     * gravidade; ver {@link Insight}.</p>
     */
    private Map<String, Insight> insights;
}
