package com.countatic.core.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Resultado agregado da análise completa de uma partida.
 *
 * <p>Contém os resultados de todas as strategies aplicadas para todos
 * os jogadores participantes. Este é o DTO final que será utilizado
 * para gerar o relatório enviado pelo Steam Bot.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchAnalysisResult {

    /** ID da partida no banco de dados. */
    private Long matchId;

    /** Nome do mapa. */
    private String mapName;

    /** Placar final (ex: "13-16"). */
    private String finalScore;

    /**
     * Lista de todos os resultados de métricas calculados.
     * Cada item representa uma categoria de métricas (Aim, Utility, etc.)
     * para um jogador específico.
     */
    private List<PlayerStatResult> playerStats;
}
