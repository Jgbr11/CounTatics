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
 * <p><b>Nota sobre Flash Efficiency:</b> é a <b>porcentagem de flashes lançadas que
 * cegaram ao menos um inimigo</b>. Cada evento {@code FLASH_BLINDED} é atribuído à
 * flash mais próxima no tempo e cada flash conta <b>no máximo uma vez</b> — é isso
 * que impede o número de passar de 100%. Quantos inimigos cada flash cega é medido
 * separadamente, por {@code enemyBlindsPerFlash}, essa sim uma razão que passa de 1
 * legitimamente. O campo {@code isEnemyFlash} distingue flash em inimigo (útil) de
 * flash em aliado (prejudicial).</p>
 *
 * <p><b>Ausência não é zero.</b> As métricas derivadas só são publicadas quando o
 * denominador delas existe: flashes lançadas para {@code flashEfficiency} e
 * {@code enemyBlindsPerFlash}, cegamentos para {@code teamFlashRate} e
 * {@code avgEnemyFlashDuration}, rounds para as médias por round. Quem não lançou
 * flash nenhuma não tem eficiência ruim — não tem eficiência. Já os contadores
 * absolutos ({@code totalFlashesThrown}, {@code totalUtilityDamage} etc.) saem
 * sempre: "lancei zero flashes" é fato medido. O raciocínio completo está no
 * comentário do bloco de crosshair em {@code AimStatStrategy.calculate}.</p>
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
        // getTotalRounds() é Integer e pode vir nulo; desempacotar direto seria
        // NPE. Mesmo tratamento de ImpactStatStrategy e MatchAnalysisService.
        int totalRounds = match.getTotalRounds() == null ? 0 : match.getTotalRounds();

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

    /**
     * Janela para associar um cegamento à flash que o causou.
     *
     * <p>{@code FlashExplode} e {@code PlayerFlashed} são emitidos praticamente
     * no mesmo tick; a folga cobre variação de tick rate e ordenação de eventos
     * sem correr o risco de capturar a flash seguinte, que dificilmente estoura
     * em menos de um segundo.</p>
     */
    private static final int JANELA_FLASH_TICKS = 64;

    /**
     * Conta quantas flashes lançadas cegaram ao menos um inimigo.
     *
     * <p>Cada cegamento é atribuído à flash mais próxima no tempo, e cada flash
     * conta no máximo uma vez — é isso que impede a métrica de passar de 100%.</p>
     */
    private long contarFlashesEfetivas(List<MatchEvent> flashesThrown, List<MatchEvent> flashBlinds) {
        Set<Integer> ticksEfetivos = new HashSet<>();

        for (MatchEvent blind : flashBlinds) {
            if (!Boolean.TRUE.equals(blind.getIsEnemyFlash())) continue;
            if (blind.getTick() == null) continue;

            MatchEvent maisProxima = null;
            int menorDistancia = Integer.MAX_VALUE;

            for (MatchEvent flash : flashesThrown) {
                if (flash.getTick() == null) continue;

                int distancia = Math.abs(blind.getTick() - flash.getTick());
                if (distancia <= JANELA_FLASH_TICKS && distancia < menorDistancia) {
                    menorDistancia = distancia;
                    maisProxima = flash;
                }
            }

            if (maisProxima != null) {
                // Set por tick: a mesma flash cegando vários inimigos entra uma vez só.
                ticksEfetivos.add(maisProxima.getTick());
            }
        }

        return ticksEfetivos.size();
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

        // ─── Flash Efficiency ────────────────────────────────────────
        // % de flashes lançadas que cegaram AO MENOS UM inimigo.
        //
        // A versão anterior dividia o número de CEGAMENTOS pelo número de
        // FLASHES: uma única flash que pegasse 3 inimigos rendia 300%, e o
        // número deixava de ser uma porcentagem. Contar flashes efetivas exige
        // correlacionar cada cegamento à flash que o causou, o que é feito por
        // proximidade de tick — FlashExplode e PlayerFlashed ocorrem
        // praticamente no mesmo instante.
        long flashesEfetivas = contarFlashesEfetivas(flashesThrown, flashBlinds);

        // Denominador: flashes lançadas. Quem não lançou flash nenhuma não tem
        // eficiência baixa — não tem eficiência. Esta é a coluna mais exposta ao
        // problema descrito em AimStatStrategy.calculate: "não joguei flash nesta
        // partida" é comuníssimo, flashEfficiency está na whitelist do
        // BaselineService com maiorEhMelhor = true, e cada 0.0 fabricado afunda a
        // distribuição e infla o percentil de quem lançou de verdade.
        //
        // Lançar flashes e não cegar ninguém é OUTRA coisa: aí o denominador
        // existe e 0.0 é desempenho medido, publicado normalmente.
        if (totalFlashesThrown > 0) {
            double flashEfficiency = (flashesEfetivas * 100.0) / totalFlashesThrown;
            metrics.put("flashEfficiency", round2(flashEfficiency));
            insights.put("flashEfficiency", generateFlashEfficiencyInsight(flashEfficiency));

            // Quantos inimigos cada flash cega, em média. Mesmo denominador.
            // Diferente da eficiência, esta razão passa de 1 legitimamente — é
            // justamente o que mede uma flash bem colocada, que pega o time inteiro.
            metrics.put("enemyBlindsPerFlash", round2((double) enemyBlinds / totalFlashesThrown));
        }

        // Team Flash Rate: % de blinds que foram em aliados.
        // Denominador: os cegamentos causados. Sem cegar ninguém não há proporção
        // de cegamentos em aliado. Cuidado: cegar só inimigos dá 0.0 legítimo — o
        // denominador existe — e continua sendo publicado.
        long totalBlinds = enemyBlinds + teamBlinds;
        if (totalBlinds > 0) {
            double teamFlashRate = (teamBlinds * 100.0) / totalBlinds;
            metrics.put("teamFlashRate", round2(teamFlashRate));
            if (teamFlashRate > 30.0) {
                insights.put("teamFlashRate",
                        String.format("⚠️ %.1f%% das suas flashes cegaram aliados! Cuidado com os line-ups.", teamFlashRate));
            }
        }

        // Average Enemy Flash Blind Duration.
        // O OptionalDouble já é a resposta certa: vazio significa que não houve
        // cegamento de inimigo com duração medida, e "duração média zero" seria
        // uma afirmação sobre flashes que não existiram.
        OptionalDouble avgDuration = flashBlinds.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsEnemyFlash()))
                .filter(e -> e.getFlashDurationSeconds() != null)
                .mapToDouble(MatchEvent::getFlashDurationSeconds)
                .average();
        if (avgDuration.isPresent()) {
            metrics.put("avgEnemyFlashDuration", round2(avgDuration.getAsDouble()));
        }

        // Totals
        metrics.put("totalFlashesThrown", (double) totalFlashesThrown);
        metrics.put("totalEnemyBlinds", (double) enemyBlinds);
        metrics.put("totalTeamBlinds", (double) teamBlinds);
        // Denominador: os rounds jogados — mesmo caso de smokesPerRound. Não
        // lançar flash nenhuma em 24 rounds é 0.0 medido e continua publicado.
        if (totalRounds > 0) {
            metrics.put("flashesPerRound", round2((double) totalFlashesThrown / totalRounds));
        }
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

        metrics.put("totalUtilityDamage", (double) totalUtilityDamage);

        // Denominador: os rounds jogados. Um jogador com rounds e 0 de dano de
        // utilitária tem 0.0 de verdade e continua publicado; sem round nenhum
        // não existe média por round a publicar.
        if (totalRounds > 0) {
            double utilityDmgPerRound = (double) totalUtilityDamage / totalRounds;
            metrics.put("utilityDamagePerRound", round2(utilityDmgPerRound));
            insights.put("utilityDamage", generateUtilityDamageInsight(utilityDmgPerRound));
        }
    }

    // ─── Smoke Usage ──────────────────────────────────────────────────

    private void calculateSmokeUsage(List<MatchEvent> allEvents, Long playerId,
                                      int totalRounds,
                                      Map<String, Double> metrics,
                                      Map<String, String> insights) {

        long smokesThrown = filterByActorAndType(allEvents, playerId, EventType.SMOKE_THROWN).size();

        metrics.put("totalSmokesThrown", (double) smokesThrown);

        // Denominador: os rounds jogados. Não lançar smoke nenhuma em 24 rounds
        // é 0.0 medido — e é justamente o que o insight abaixo aponta. Sem round
        // nenhum, a média não existe.
        if (totalRounds > 0) {
            double smokesPerRound = (double) smokesThrown / totalRounds;
            metrics.put("smokesPerRound", round2(smokesPerRound));

            if (smokesPerRound < 0.3) {
                insights.put("smokeUsage",
                        String.format("Você lançou apenas %.1f smokes por round. Use mais smokes para controlar o mapa!", smokesPerRound));
            }
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
