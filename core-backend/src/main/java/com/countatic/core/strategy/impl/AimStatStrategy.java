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
        List<Double> erros = errosAngulares(weaponFires);

        double crosshairScore = calculateCrosshairPlacement(erros);
        metrics.put("crosshairPlacementScore", round2(crosshairScore));

        // Só publica as métricas de mira se houver disparos avaliáveis.
        // Devolver 0.0 quando não há dado seria indistinguível de "mira péssima".
        if (!erros.isEmpty()) {
            metrics.put("medianCrosshairErrorDegrees", round2(erroAngularMediano(erros)));
            metrics.put("evaluatedShots", (double) erros.size());
            insights.put("crosshairPlacementScore",
                    generateCrosshairInsight(crosshairScore, erroAngularMediano(erros)));
        }

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
     * Erro angular considerado "mira bem posicionada".
     *
     * <p>5° a 10 metros equivale a aproximadamente meio corpo de largura —
     * perto o bastante para que o ajuste até a cabeça seja um micro-movimento,
     * e não um giro.</p>
     */
    private static final double LIMIAR_BOA_MIRA_GRAUS = 5.0;

    /**
     * Erro angular entre a mira e a cabeça do inimigo, em graus, para cada disparo.
     *
     * <p><b>O que mudou.</b> A versão anterior media o valor absoluto do pitch:
     * considerava boa mira todo disparo feito perto da linha do horizonte,
     * <i>ignorando completamente onde o inimigo estava</i>. Um jogador olhando
     * para uma parede vazia pontuava tão bem quanto um com a mira na cabeça do
     * adversário. Na prática a métrica media "você olhava para o horizonte".</p>
     *
     * <p>Agora o parser Go anexa a cabeça do inimigo mais próximo do centro da
     * mira (ele tem o estado do jogo; o Java não teria como saber). Aqui o
     * cálculo é o ângulo entre a direção da visão e a direção até essa cabeça.</p>
     *
     * <p>Disparos sem inimigo dentro do cone frontal não entram: spray em
     * parede e tiro às cegas não dizem nada sobre posicionamento de mira.</p>
     */
    private List<Double> errosAngulares(List<MatchEvent> weaponFires) {
        List<Double> erros = new ArrayList<>();

        for (MatchEvent e : weaponFires) {
            if (e.getViewAngleX() == null || e.getViewAngleY() == null) continue;
            if (e.getActorPositionX() == null || e.getVictimPositionX() == null) continue;

            // Direção da mira. Convenção da Source: yaw 0° aponta para +X e
            // cresce no anti-horário; pitch POSITIVO é para BAIXO.
            double yaw = Math.toRadians(e.getViewAngleX());
            double pitch = Math.toRadians(e.getViewAngleY());

            double mx = Math.cos(pitch) * Math.cos(yaw);
            double my = Math.cos(pitch) * Math.sin(yaw);
            double mz = -Math.sin(pitch);

            // Direção dos olhos até a cabeça do inimigo.
            double dx = e.getVictimPositionX() - e.getActorPositionX();
            double dy = e.getVictimPositionY() - e.getActorPositionY();
            double dz = e.getVictimPositionZ() - e.getActorPositionZ();

            double norma = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (norma == 0) continue;

            double cos = (mx * dx + my * dy + mz * dz) / norma;
            // Erro de ponto flutuante pode passar de [-1,1] e fazer acos virar NaN.
            cos = Math.max(-1.0, Math.min(1.0, cos));

            erros.add(Math.toDegrees(Math.acos(cos)));
        }

        return erros;
    }

    /**
     * Score de crosshair placement: % de disparos com a mira a até
     * {@value #LIMIAR_BOA_MIRA_GRAUS}° da cabeça do inimigo.
     *
     * @return 0.0 a 100.0; 0.0 quando não há disparo avaliável
     */
    private double calculateCrosshairPlacement(List<Double> erros) {
        if (erros.isEmpty()) return 0.0;

        long bons = erros.stream().filter(a -> a <= LIMIAR_BOA_MIRA_GRAUS).count();
        return (bons * 100.0) / erros.size();
    }

    /**
     * Erro angular mediano, em graus. Menor é melhor.
     *
     * <p>Complementa o score percentual: a mediana resiste a outliers (um
     * disparo com 40° de erro num duelo perdido não afunda o número) e é
     * diretamente interpretável — "sua mira fica em média a X° da cabeça".</p>
     */
    private double erroAngularMediano(List<Double> erros) {
        if (erros.isEmpty()) return 0.0;

        List<Double> ordenados = new ArrayList<>(erros);
        Collections.sort(ordenados);

        int meio = ordenados.size() / 2;
        return ordenados.size() % 2 == 0
                ? (ordenados.get(meio - 1) + ordenados.get(meio)) / 2.0
                : ordenados.get(meio);
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

    private String generateCrosshairInsight(double score, double erroMediano) {
        if (score >= 60.0) {
            return String.format(
                    "Crosshair placement excelente: em %.0f%% dos disparos a mira estava a menos de %.0f° "
                            + "da cabeça (erro mediano de %.1f°).",
                    score, LIMIAR_BOA_MIRA_GRAUS, erroMediano);
        }
        if (score >= 35.0) {
            return String.format(
                    "Crosshair placement razoável: %.0f%% dos disparos com a mira já perto da cabeça "
                            + "(erro mediano de %.1f°). Pré-mirar os ângulos antes de cruzá-los reduz esse erro.",
                    score, erroMediano);
        }
        return String.format(
                "Crosshair placement a melhorar: erro mediano de %.1f° até a cabeça do inimigo. "
                        + "Ande com a mira na altura da cabeça e pré-mirando os cantos — assim o duelo "
                        + "vira um micro-ajuste em vez de um giro.",
                erroMediano);
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
