package com.countatic.core.strategy.impl;

import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.StatCalculationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Strategy para cálculo de métricas de <b>utilitárias (Utility)</b>.
 *
 * <p>Métricas calculadas:</p>
 * <ul>
 *   <li><b>Flash Efficiency:</b> Percentual de flashbangs que cegaram inimigos vs. total lançadas.</li>
 *   <li><b>Team Flash Rate:</b> Percentual de flashbangs que cegaram aliados (quanto menor, melhor).</li>
 *   <li><b>Average Flash Blind Duration:</b> Tempo médio que as flashes cegaram inimigos.</li>
 *   <li><b>Utility Damage per Round:</b> Dano médio por round causado por HE e Molotov.</li>
 *   <li><b>Total Utility Damage:</b> Dano total causado por HE e Molotov na partida.</li>
 *   <li><b>Smokes Thrown per Round:</b> Média de smokes lançadas por round.</li>
 * </ul>
 *
 * <p><b>Nota sobre Flash Efficiency:</b> Diferente de simplesmente contar flashes lançadas,
 * esta métrica considera os eventos {@code FLASH_BLINDED} associados a cada flash. Uma flash
 * que cega 3 inimigos conta mais do que uma que cega apenas 1. O campo {@code isEnemyFlash}
 * distingue entre flash em inimigo (útil) e em aliado (prejudicial).</p>
 */
@Slf4j
@Component
public class UtilityStatStrategy implements StatCalculationStrategy {

    private static final String CATEGORY = "Utility";

    /** Armas utilitárias que causam dano (para cálculo de utility damage). */
    private static final Set<String> DAMAGE_UTILITIES = Set.of(
            "hegrenade", "he_grenade", "inferno", "molotov", "incgrenade", "inc_grenade"
    );

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public PlayerStatResult calculate(Match match, Player player) {
        log.debug("Calculando métricas de Utility para jogador {} na partida {}",
                player.getSteamId64(), match.getId());

        Map<String, Double> metrics = new LinkedHashMap<>();
        Map<String, String> insights = new LinkedHashMap<>();

        List<MatchEvent> allEvents = flattenEvents(match);
        Long playerId = player.getId();
        int totalRounds = match.getTotalRounds();

        // ─── 1. Flash Analysis ────────────────────────────────────────────
        calculateFlashMetrics(allEvents, playerId, totalRounds, metrics, insights);

        // ─── 2. Utility Damage ────────────────────────────────────────────
        calculateUtilityDamage(allEvents, playerId, totalRounds, metrics, insights);

        // ─── 3. Smoke Usage ───────────────────────────────────────────────
        calculateSmokeUsage(allEvents, playerId, totalRounds, metrics, insights);

        // ─── 4. HE & Molotov Usage Counts ─────────────────────────────────
        long heThrown = filterByActorAndType(allEvents, playerId, EventType.HE_THROWN).size();
        long molotovThrown = filterByActorAndType(allEvents, playerId, EventType.MOLOTOV_THROWN).size();
        metrics.put("totalHEThrown", (double) heThrown);
        metrics.put("totalMolotovThrown", (double) molotovThrown);

        log.info("Utility stats calculados para {}: flashEff={}, utilDmg/R={}",
                player.getDisplayName(),
                metrics.get("flashEfficiency"),
                metrics.get("utilityDamagePerRound"));

        return PlayerStatResult.builder()
                .category(CATEGORY)
                .steamId64(player.getSteamId64())
                .playerName(player.getDisplayName())
                .metrics(metrics)
                .insights(insights)
                .build();
    }

    // ─── Flash Metrics ────────────────────────────────────────────────

    private void calculateFlashMetrics(List<MatchEvent> allEvents, Long playerId,
                                       int totalRounds,
                                       Map<String, Double> metrics,
                                       Map<String, String> insights) {

        List<MatchEvent> flashesThrown = filterByActorAndType(allEvents, playerId, EventType.FLASH_THROWN);
        List<MatchEvent> flashBlinds = filterByActorAndType(allEvents, playerId, EventType.FLASH_BLINDED);

        long totalFlashesThrown = flashesThrown.size();
        long enemyBlinds = flashBlinds.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsEnemyFlash()))
                .count();
        long teamBlinds = flashBlinds.stream()
                .filter(e -> Boolean.FALSE.equals(e.getIsEnemyFlash()))
                .count();

        // Flash Efficiency: % de flashes que cegaram pelo menos um inimigo
        // Aqui usamos a proporção de blinds em inimigos vs. total de flashes lançadas
        double flashEfficiency = totalFlashesThrown > 0
                ? (enemyBlinds * 100.0) / totalFlashesThrown
                : 0.0;
        metrics.put("flashEfficiency", round2(flashEfficiency));
        insights.put("flashEfficiency", generateFlashEfficiencyInsight(flashEfficiency));

        // Team Flash Rate: % de blinds que foram em aliados
        long totalBlinds = enemyBlinds + teamBlinds;
        double teamFlashRate = totalBlinds > 0
                ? (teamBlinds * 100.0) / totalBlinds
                : 0.0;
        metrics.put("teamFlashRate", round2(teamFlashRate));
        if (teamFlashRate > 30.0) {
            insights.put("teamFlashRate",
                    String.format("⚠️ %.1f%% das suas flashes cegaram aliados! Cuidado com os line-ups.", teamFlashRate));
        }

        // Average Enemy Flash Blind Duration
        OptionalDouble avgDuration = flashBlinds.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsEnemyFlash()))
                .filter(e -> e.getFlashDurationSeconds() != null)
                .mapToDouble(MatchEvent::getFlashDurationSeconds)
                .average();
        metrics.put("avgEnemyFlashDuration", round2(avgDuration.orElse(0.0)));

        // Totals
        metrics.put("totalFlashesThrown", (double) totalFlashesThrown);
        metrics.put("totalEnemyBlinds", (double) enemyBlinds);
        metrics.put("totalTeamBlinds", (double) teamBlinds);
        metrics.put("flashesPerRound", totalRounds > 0
                ? round2((double) totalFlashesThrown / totalRounds)
                : 0.0);
    }

    // ─── Utility Damage ───────────────────────────────────────────────

    private void calculateUtilityDamage(List<MatchEvent> allEvents, Long playerId,
                                         int totalRounds,
                                         Map<String, Double> metrics,
                                         Map<String, String> insights) {

        List<MatchEvent> utilityDamageEvents = allEvents.stream()
                .filter(e -> e.getEventType() == EventType.DAMAGE)
                .filter(e -> e.getActor() != null && e.getActor().getId().equals(playerId))
                .filter(e -> e.getWeapon() != null && DAMAGE_UTILITIES.contains(e.getWeapon().toLowerCase()))
                .toList();

        int totalUtilityDamage = utilityDamageEvents.stream()
                .filter(e -> e.getDamageAmount() != null)
                .mapToInt(MatchEvent::getDamageAmount)
                .sum();

        double utilityDmgPerRound = totalRounds > 0
                ? (double) totalUtilityDamage / totalRounds
                : 0.0;

        metrics.put("totalUtilityDamage", (double) totalUtilityDamage);
        metrics.put("utilityDamagePerRound", round2(utilityDmgPerRound));
        insights.put("utilityDamage", generateUtilityDamageInsight(utilityDmgPerRound));
    }

    // ─── Smoke Usage ──────────────────────────────────────────────────

    private void calculateSmokeUsage(List<MatchEvent> allEvents, Long playerId,
                                      int totalRounds,
                                      Map<String, Double> metrics,
                                      Map<String, String> insights) {

        long smokesThrown = filterByActorAndType(allEvents, playerId, EventType.SMOKE_THROWN).size();
        double smokesPerRound = totalRounds > 0
                ? (double) smokesThrown / totalRounds
                : 0.0;

        metrics.put("totalSmokesThrown", (double) smokesThrown);
        metrics.put("smokesPerRound", round2(smokesPerRound));

        if (smokesPerRound < 0.3 && totalRounds > 0) {
            insights.put("smokeUsage",
                    String.format("Você lançou apenas %.1f smokes por round. Use mais smokes para controlar o mapa!", smokesPerRound));
        }
    }

    // ─── Geração de Insights ──────────────────────────────────────────

    private String generateFlashEfficiencyInsight(double efficiency) {
        if (efficiency >= 70.0) {
            return String.format("Flash efficiency excelente (%.1f%%)! Suas flashes estão cegando inimigos consistentemente.", efficiency);
        } else if (efficiency >= 45.0) {
            return String.format("Flash efficiency boa (%.1f%%). Tente aprender mais line-ups para pop-flashes efetivas.", efficiency);
        } else if (efficiency >= 20.0) {
            return String.format("Flash efficiency mediana (%.1f%%). Muitas das suas flashes não cegam ninguém. Pratique line-ups específicos.", efficiency);
        } else {
            return String.format("Flash efficiency baixa (%.1f%%). A maioria das suas flashes está sendo desperdiçada. Estude pop-flashes e posições comuns.", efficiency);
        }
    }

    private String generateUtilityDamageInsight(double dmgPerRound) {
        if (dmgPerRound >= 15.0) {
            return String.format("Excelente uso de HE e Molotov (%.1f dmg/round). Você está usando utilitárias de forma impactante.", dmgPerRound);
        } else if (dmgPerRound >= 7.0) {
            return String.format("Bom dano de utilitárias (%.1f dmg/round). Tente combinar HE com Molotov em posições comuns.", dmgPerRound);
        } else {
            return String.format("Dano de utilitárias baixo (%.1f dmg/round). Jogue mais HE e Molotovs em posições previsíveis de inimigos.", dmgPerRound);
        }
    }

    // ─── Utilitários ──────────────────────────────────────────────────

    private List<MatchEvent> flattenEvents(Match match) {
        return match.getRounds().stream()
                .flatMap(r -> r.getEvents().stream())
                .toList();
    }

    private List<MatchEvent> filterByActorAndType(List<MatchEvent> events, Long playerId, EventType type) {
        return events.stream()
                .filter(e -> e.getEventType() == type)
                .filter(e -> e.getActor() != null && e.getActor().getId().equals(playerId))
                .toList();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
