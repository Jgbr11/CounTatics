package com.countatic.core.strategy;

import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.Match;
import com.countatic.core.entity.Player;

/**
 * Interface do padrão Strategy para cálculo de estatísticas de CS2.
 *
 * <p>Cada implementação desta interface é responsável por calcular uma
 * <b>categoria específica</b> de métricas para um jogador em uma partida.
 * O {@code MatchAnalysisService} itera sobre todas as strategies registradas
 * via injeção de dependências, aplicando cada uma automaticamente.</p>
 *
 * <h3>Princípios de Design:</h3>
 * <ul>
 *   <li><b>Single Responsibility:</b> Cada Strategy calcula apenas uma categoria
 *       (Aim, Utility, Positioning, etc.).</li>
 *   <li><b>Open/Closed:</b> Novas categorias de métricas são adicionadas criando
 *       novas implementações, sem modificar código existente.</li>
 *   <li><b>Plugabilidade:</b> Basta anotar com {@code @Component} para que o
 *       Spring injete automaticamente na lista de strategies do service.</li>
 * </ul>
 *
 * <h3>Contrato:</h3>
 * <ul>
 *   <li>{@link #getCategory()} retorna o nome da categoria (ex: "Aim", "Utility").</li>
 *   <li>{@link #calculate(Match, Player)} recebe a partida <b>com rounds e eventos já carregados</b>
 *       e o jogador alvo, retornando as métricas calculadas e insights.</li>
 * </ul>
 *
 * @see com.countatic.core.strategy.impl.AimStatStrategy
 * @see com.countatic.core.strategy.impl.UtilityStatStrategy
 */
public interface StatCalculationStrategy {

    /**
     * Retorna o nome da categoria de métricas que esta strategy calcula.
     *
     * @return nome da categoria (ex: "Aim", "Utility")
     */
    String getCategory();

    /**
     * Calcula as métricas da categoria para um jogador específico em uma partida.
     *
     * <p>A implementação deve:</p>
     * <ol>
     *   <li>Filtrar os eventos relevantes da partida para o jogador</li>
     *   <li>Aplicar os cálculos matemáticos</li>
     *   <li>Gerar insights/dicas baseados nos resultados</li>
     * </ol>
     *
     * @param match  a partida com rounds e eventos já carregados (eager ou fetch join)
     * @param player o jogador para o qual calcular as métricas
     * @return resultado contendo métricas numéricas e insights textuais
     */
    PlayerStatResult calculate(Match match, Player player);
}
