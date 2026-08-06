package com.countatic.core.strategy.impl;

import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.StatCalculationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Strategy para cálculo de métricas de <b>mira (Aim)</b>.
 *
 * <p>Métricas calculadas:</p>
 * <ul>
 *   <li><b>Headshot Percentage (HS%):</b> Proporção de kills que foram headshot.</li>
 *   <li><b>Crosshair Placement Score:</b> Mede o quão próximo da altura da cabeça
 *       o jogador mantém a mira (baseado no viewAngle Y/pitch dos eventos WEAPON_FIRE).</li>
 *   <li><b>Kills per Round (KPR):</b> Média de eliminações por round.</li>
 *   <li><b>Deaths per Round (DPR):</b> Média de mortes por round.</li>
 *   <li><b>Kill/Death Ratio (K/D):</b> Razão entre kills e deaths.</li>
 * </ul>
 *
 * <p><b>Nota sobre Crosshair Placement:</b> O cálculo real envolve a análise do
 * ângulo vertical (pitch) de cada disparo em relação à posição vertical esperada
 * da cabeça do inimigo. Valores mais próximos de 0° (nível da cabeça) indicam
 * melhor posicionamento de mira. A fórmula exata será refinada em iterações
 * futuras após discussão com o usuário sobre a matemática.</p>
 */
@Slf4j
@Component
public class AimStatStrategy implements StatCalculationStrategy {

    private static final String CATEGORY = "Aim";

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public PlayerStatResult calculate(Match match, Player player) {
        log.debug("Calculando métricas de Aim para jogador {} na partida {}",
                player.getSteamId64(), match.getId());

        Map<String, Double> metrics = new LinkedHashMap<>();
        Map<String, String> insights = new LinkedHashMap<>();

        List<MatchEvent> allEvents = flattenEvents(match);
        Long playerId = player.getId();
        int totalRounds = match.getTotalRounds();

        // ─── 1. Headshot Percentage ───────────────────────────────────────
        List<MatchEvent> kills = filterByActorAndType(allEvents, playerId, EventType.KILL);
        long headshotKills = kills.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsHeadshot()))
                .count();

        double hsPercentage = kills.isEmpty() ? 0.0 : (headshotKills * 100.0) / kills.size();
        metrics.put("headshotPercentage", round2(hsPercentage));
        insights.put("headshotPercentage", generateHsInsight(hsPercentage));

        // ─── 2. Kills per Round (KPR) ────────────────────────────────────
        double kpr = totalRounds > 0 ? (double) kills.size() / totalRounds : 0.0;
        metrics.put("killsPerRound", round2(kpr));

        // ─── 3. Deaths per Round (DPR) ───────────────────────────────────
        List<MatchEvent> deaths = filterByVictimAndType(allEvents, playerId, EventType.KILL);
        double dpr = totalRounds > 0 ? (double) deaths.size() / totalRounds : 0.0;
        metrics.put("deathsPerRound", round2(dpr));

        // ─── 4. K/D Ratio ────────────────────────────────────────────────
        double kdRatio = deaths.isEmpty() ? kills.size() : (double) kills.size() / deaths.size();
        metrics.put("kdRatio", round2(kdRatio));
        insights.put("kdRatio", generateKdInsight(kdRatio));

        // ─── 5. Crosshair Placement Score ────────────────────────────────
        // Baseado nos eventos WEAPON_FIRE: analisa o ângulo vertical (pitch/viewAngleY)
        // O score mede a % de disparos em que o pitch estava dentro de um threshold
        // "ideal" para headshot level (próximo de 0° no eixo vertical).
        //
        // NOTA: A fórmula será refinada quando discutirmos a matemática detalhada.
        // Por ora, usamos um cálculo simplificado baseado em threshold de ângulo.
        List<MatchEvent> weaponFires = filterByActorAndType(allEvents, playerId, EventType.WEAPON_FIRE);
        double crosshairScore = calculateCrosshairPlacement(weaponFires);
        metrics.put("crosshairPlacementScore", round2(crosshairScore));
        insights.put("crosshairPlacementScore", generateCrosshairInsight(crosshairScore));

        // ─── 6. Total Kills / Deaths (valores absolutos) ─────────────────
        metrics.put("totalKills", (double) kills.size());
        metrics.put("totalDeaths", (double) deaths.size());
        metrics.put("totalHeadshotKills", (double) headshotKills);

        log.info("Aim stats calculados para {}: HS%={}, KPR={}, K/D={}",
                player.getDisplayName(), metrics.get("headshotPercentage"),
                metrics.get("killsPerRound"), metrics.get("kdRatio"));

        return PlayerStatResult.builder()
                .category(CATEGORY)
                .steamId64(player.getSteamId64())
                .playerName(player.getDisplayName())
                .metrics(metrics)
                .insights(insights)
                .build();
    }

    // ─── Cálculos Internos ────────────────────────────────────────────

    /**
     * Calcula o score de crosshair placement baseado no ângulo vertical dos disparos.
     *
     * <p>Lógica simplificada (placeholder para refinamento futuro):</p>
     * <ul>
     *   <li>Coleta o viewAngleY (pitch) de cada WEAPON_FIRE</li>
     *   <li>Pitch próximo de 0° = nível da cabeça (ideal)</li>
     *   <li>Score = % de disparos com |pitch| ≤ threshold</li>
     * </ul>
     *
     * @param weaponFires eventos de disparo do jogador
     * @return score de 0.0 a 100.0
     */
    private double calculateCrosshairPlacement(List<MatchEvent> weaponFires) {
        if (weaponFires.isEmpty()) {
            return 0.0;
        }

        // Threshold: disparos com pitch dentro de ±5° do headshot level
        // são considerados "bom posicionamento de mira"
        // TODO: Refinar esta fórmula com dados reais e discussão do cálculo
        final double GOOD_PLACEMENT_THRESHOLD = 5.0;

        long goodPlacementCount = weaponFires.stream()
                .filter(e -> e.getViewAngleY() != null)
                .filter(e -> Math.abs(e.getViewAngleY()) <= GOOD_PLACEMENT_THRESHOLD)
                .count();

        long totalWithAngle = weaponFires.stream()
                .filter(e -> e.getViewAngleY() != null)
                .count();

        if (totalWithAngle == 0) {
            return 0.0;
        }

        return (goodPlacementCount * 100.0) / totalWithAngle;
    }

    // ─── Geração de Insights ──────────────────────────────────────────

    private String generateHsInsight(double hsPercentage) {
        if (hsPercentage >= 60.0) {
            return String.format("Excelente HS%% (%.1f%%)! Sua mira na cabeça está em nível profissional.", hsPercentage);
        } else if (hsPercentage >= 45.0) {
            return String.format("Bom HS%% (%.1f%%). Acima da média. Continue treinando aim maps para melhorar ainda mais.", hsPercentage);
        } else if (hsPercentage >= 30.0) {
            return String.format("HS%% na média (%.1f%%). Tente focar mais na altura da cabeça ao mirar.", hsPercentage);
        } else {
            return String.format("HS%% abaixo da média (%.1f%%). Pratique mira em aim_botz e deathmatch focando em headshots.", hsPercentage);
        }
    }

    private String generateKdInsight(double kdRatio) {
        if (kdRatio >= 1.5) {
            return String.format("K/D excelente (%.2f). Você está dominando os duelos!", kdRatio);
        } else if (kdRatio >= 1.0) {
            return String.format("K/D positivo (%.2f). Sólido — continue mantendo essa consistência.", kdRatio);
        } else if (kdRatio >= 0.8) {
            return String.format("K/D levemente negativo (%.2f). Foque em posicionamento para pegar vantagem nos duelos.", kdRatio);
        } else {
            return String.format("K/D baixo (%.2f). Revise seu posicionamento e escolha de duelos.", kdRatio);
        }
    }

    private String generateCrosshairInsight(double score) {
        if (score >= 75.0) {
            return String.format("Crosshair placement excelente (%.1f%%). Sua mira já está posicionada para headshot na maioria dos disparos.", score);
        } else if (score >= 50.0) {
            return String.format("Crosshair placement bom (%.1f%%). Tente manter a mira mais na altura da cabeça ao andar pelo mapa.", score);
        } else {
            return String.format("Crosshair placement precisa melhorar (%.1f%%). Pratique manter a mira na altura da cabeça constantemente.", score);
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

    private List<MatchEvent> filterByVictimAndType(List<MatchEvent> events, Long playerId, EventType type) {
        return events.stream()
                .filter(e -> e.getEventType() == type)
                .filter(e -> e.getVictim() != null && e.getVictim().getId().equals(playerId))
                .toList();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
