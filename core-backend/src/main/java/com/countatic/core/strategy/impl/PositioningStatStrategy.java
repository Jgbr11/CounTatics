package com.countatic.core.strategy.impl;

import com.countatic.core.dto.stats.Insight;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.StatCalculationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy de <b>posicionamento</b> — onde e quando os duelos acontecem.
 *
 * <p>As outras três respondem "o quanto você acertou". Esta responde "em que
 * situação você se colocou", que costuma explicar a primeira: quem perde a
 * maioria dos duelos longos não tem problema de mira, tem problema de escolha
 * de duelo, e o treino é outro.</p>
 *
 * <p><b>Tudo aqui sai de dado que já estava no banco.</b> O parser grava a
 * posição do matador e da vítima em cada kill, e o round guarda o tick de
 * início. Nenhuma imagem de mapa é necessária — a análise é sobre distância e
 * tempo, não sobre o lugar no mapa.</p>
 *
 * <p><b>Por que não é um mapa de calor.</b> Um heatmap entrega uma figura para
 * o jogador interpretar, e depende de sobrepor o radar de cada mapa com as
 * constantes de conversão da Valve. Estas métricas entregam a conclusão
 * pronta — "você vence de perto e perde de longe" — sem asset externo.</p>
 */
@Slf4j
@Component
public class PositioningStatStrategy implements StatCalculationStrategy {

    private static final String CATEGORY = "Posicionamento";

    /**
     * Unidades de jogo por metro.
     *
     * <p>Uma unidade Hammer equivale a 1,905 cm, então um metro tem ~52,5. A
     * conversão existe só para o número ser legível: "duelo de 28 m" diz algo
     * ao jogador, "duelo de 1470 unidades" não.</p>
     */
    private static final double UNIDADES_POR_METRO = 52.5;

    /** Até aqui é briga de perto — SMG, shotgun, entry. */
    private static final double CURTO_METROS = 10.0;

    /** A partir daqui é duelo de rifle parado ou AWP. */
    private static final double LONGO_METROS = 25.0;

    /**
     * Duelos mínimos numa faixa para publicar a taxa dela.
     *
     * <p>Com um ou dois duelos, "100% de vitória" é ruído com cara de
     * estatística. Abaixo disso a métrica é omitida, seguindo a mesma regra das
     * demais: ausência não vira zero.</p>
     */
    private static final int DUELOS_MINIMOS = 3;

    /** Janela inicial do round. Morrer aqui é morrer na entrada. */
    private static final double SEGUNDOS_ENTRADA = 15.0;

    /**
     * Diferença de altura que caracteriza vantagem de posição.
     *
     * <p>~1,2 m: menos que isso é degrau ou rampa, não "estar por cima".</p>
     */
    private static final double ALTURA_VANTAGEM_UNIDADES = 64.0;

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public PlayerStatResult calculate(Match match, Player player) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        Map<String, Insight> insights = new LinkedHashMap<>();

        Long playerId = player.getId();
        int tickRate = (match.getTickRate() == null || match.getTickRate() <= 0)
                ? 64 : match.getTickRate();

        List<Double> distanciasKill = new ArrayList<>();
        List<Double> distanciasMorte = new ArrayList<>();
        List<Double> segundosDaMorte = new ArrayList<>();

        // Vitórias e derrotas por faixa de distância. O índice é a faixa:
        // 0 curto, 1 médio, 2 longo.
        int[] ganhos = new int[3];
        int[] perdas = new int[3];

        int mortesNaEntrada = 0;
        int mortesPorCima = 0;
        int mortesComAltura = 0;

        for (Round round : match.getRounds()) {
            for (MatchEvent e : round.getEvents()) {
                if (e.getEventType() != EventType.KILL) continue;

                boolean matou = ehAtor(e, playerId);
                boolean morreu = ehVitima(e, playerId);
                if (!matou && !morreu) continue;

                // Fogo amigo não é duelo: não diz nada sobre escolha de posição.
                if (mesmoLado(e)) continue;

                Double distancia = distanciaEmMetros(e);
                if (distancia != null) {
                    int faixa = faixaDe(distancia);
                    if (matou) {
                        distanciasKill.add(distancia);
                        ganhos[faixa]++;
                    } else {
                        distanciasMorte.add(distancia);
                        perdas[faixa]++;
                    }
                }

                if (morreu) {
                    Double segundo = segundoNoRound(e, round, tickRate);
                    if (segundo != null) {
                        segundosDaMorte.add(segundo);
                        if (segundo <= SEGUNDOS_ENTRADA) mortesNaEntrada++;
                    }

                    Boolean porCima = matadorEstavaAcima(e);
                    if (porCima != null) {
                        mortesComAltura++;
                        if (porCima) mortesPorCima++;
                    }
                }
            }
        }

        int totalDuelos = distanciasKill.size() + distanciasMorte.size();
        metrics.put("totalDuels", (double) totalDuelos);

        // ─── Distância dos duelos ────────────────────────────────────
        // Separadas de propósito: matar de perto e morrer de longe é um
        // diagnóstico; a média das duas juntas esconderia isso.
        if (!distanciasKill.isEmpty()) {
            metrics.put("avgKillDistance", round2(media(distanciasKill)));
        }
        if (!distanciasMorte.isEmpty()) {
            metrics.put("avgDeathDistance", round2(media(distanciasMorte)));
        }

        publicarTaxa(metrics, "closeRangeWinRate", ganhos[0], perdas[0]);
        publicarTaxa(metrics, "midRangeWinRate", ganhos[1], perdas[1]);
        publicarTaxa(metrics, "longRangeWinRate", ganhos[2], perdas[2]);

        Insight duelo = insightDuelos(metrics);
        if (duelo != null) insights.put("duelRange", duelo);

        // ─── Momento da morte ────────────────────────────────────────
        if (!segundosDaMorte.isEmpty()) {
            double medio = media(segundosDaMorte);
            metrics.put("avgDeathTimeSeconds", round2(medio));

            double taxaEntrada = (mortesNaEntrada * 100.0) / segundosDaMorte.size();
            metrics.put("earlyDeathRate", round2(taxaEntrada));

            insights.put("deathTiming", insightMomento(medio, taxaEntrada, mortesNaEntrada,
                    segundosDaMorte.size()));
        }

        // ─── Altura ──────────────────────────────────────────────────
        // Denominador é só o que teve altura medida: eventos sem posição
        // ficam fora, em vez de contar como "não foi por cima".
        if (mortesComAltura >= DUELOS_MINIMOS) {
            double taxa = (mortesPorCima * 100.0) / mortesComAltura;
            metrics.put("deathsFromAboveRate", round2(taxa));
            if (taxa >= 45.0) {
                insights.put("highGround", Insight.aviso(String.format(
                        "%.0f%% das suas mortes vieram de um inimigo em posição mais alta. "
                                + "Antes de cruzar, olhe para cima: sacadas e caixas são o "
                                + "ângulo que menos se checa.", taxa)));
            }
        }

        log.debug("Posicionamento de {}: {} duelos, morte média aos {}s",
                player.getDisplayName(), totalDuelos, metrics.get("avgDeathTimeSeconds"));

        return PlayerStatResult.builder()
                .category(CATEGORY)
                .steamId64(player.getSteamId64())
                .playerName(player.getDisplayName())
                .metrics(metrics)
                .insights(insights)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CÁLCULOS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Distância 3D entre matador e vítima, em metros.
     *
     * <p>Devolve {@code null} quando o parser não anexou alguma das posições —
     * acontece em kills por bomba ou queda, que não são duelo de qualquer
     * forma.</p>
     */
    private Double distanciaEmMetros(MatchEvent e) {
        if (e.getActorPositionX() == null || e.getVictimPositionX() == null
                || e.getActorPositionY() == null || e.getVictimPositionY() == null) {
            return null;
        }

        double dx = e.getActorPositionX() - e.getVictimPositionX();
        double dy = e.getActorPositionY() - e.getVictimPositionY();
        double dz = (e.getActorPositionZ() == null || e.getVictimPositionZ() == null)
                ? 0 : e.getActorPositionZ() - e.getVictimPositionZ();

        return Math.sqrt(dx * dx + dy * dy + dz * dz) / UNIDADES_POR_METRO;
    }

    private int faixaDe(double metros) {
        if (metros < CURTO_METROS) return 0;
        if (metros <= LONGO_METROS) return 1;
        return 2;
    }

    /** Segundo do round em que o evento aconteceu. */
    private Double segundoNoRound(MatchEvent e, Round round, int tickRate) {
        if (e.getTick() == null || round.getStartTick() == null) return null;
        double s = (e.getTick() - round.getStartTick()) / (double) tickRate;
        // Tick anterior ao início do round é dado inconsistente, não um evento
        // de tempo negativo.
        return s < 0 ? null : s;
    }

    /** {@code null} quando não dá para saber a altura relativa. */
    private Boolean matadorEstavaAcima(MatchEvent e) {
        if (e.getActorPositionZ() == null || e.getVictimPositionZ() == null) return null;
        return e.getActorPositionZ() - e.getVictimPositionZ() >= ALTURA_VANTAGEM_UNIDADES;
    }

    private void publicarTaxa(Map<String, Double> metrics, String chave, int ganhos, int perdas) {
        int total = ganhos + perdas;
        if (total < DUELOS_MINIMOS) return;
        metrics.put(chave, round2((ganhos * 100.0) / total));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INSIGHTS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Compara o desempenho entre faixas de distância.
     *
     * <p>A diferença é o que informa, não o valor absoluto: vencer 45% dos
     * duelos é mediano, mas vencer 70% de perto e 25% de longe é um problema
     * de <i>escolha</i> de duelo, com treino específico.</p>
     */
    private Insight insightDuelos(Map<String, Double> metrics) {
        Double perto = metrics.get("closeRangeWinRate");
        Double longe = metrics.get("longRangeWinRate");

        if (perto == null || longe == null) {
            // Sem as duas pontas não há comparação a fazer. Uma só vira
            // constatação, e disso os cards já dão conta.
            return null;
        }

        double diferenca = perto - longe;

        if (diferenca >= 20) {
            return Insight.aviso(String.format(
                    "Você vence %.0f%% dos duelos curtos, mas só %.0f%% dos longos. "
                            + "O problema não é mira, é distância: force o combate para perto "
                            + "com utilitária e evite trocar tiro parado com rifle a %.0f m+.",
                    perto, longe, LONGO_METROS));
        }
        if (diferenca <= -20) {
            return Insight.aviso(String.format(
                    "Você vence %.0f%% dos duelos longos, mas só %.0f%% dos curtos. "
                            + "Segure os ângulos abertos e evite entrar em espaço fechado "
                            + "primeiro — deixe o duelo curto para quem entra com você.",
                    longe, perto));
        }
        return Insight.sucesso(String.format(
                "Seu desempenho é parecido de perto (%.0f%%) e de longe (%.0f%%). "
                        + "Isso dá liberdade de função: você não precisa evitar nenhum tipo de duelo.",
                perto, longe));
    }

    private Insight insightMomento(double medio, double taxaEntrada, int naEntrada, int total) {
        if (taxaEntrada >= 40.0) {
            return Insight.aviso(String.format(
                    "%d das suas %d mortes aconteceram nos primeiros %.0f s do round. "
                            + "Morrer na entrada sem trocar custa o round inteiro ao time — "
                            + "entre depois da utilitária, ou deixe outro abrir.",
                    naEntrada, total, SEGUNDOS_ENTRADA));
        }
        if (medio >= 60.0) {
            return Insight.info(String.format(
                    "Você morre em média aos %.0f s do round, bem tarde. "
                            + "Sobreviver é bom, mas rounds decididos no tempo costumam "
                            + "significar espaço cedido cedo demais.", medio));
        }
        return Insight.sucesso(String.format(
                "Suas mortes se distribuem bem pelo round (média aos %.0f s), "
                        + "sem concentração na entrada.", medio));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UTILITÁRIOS
    // ═══════════════════════════════════════════════════════════════════

    private boolean ehAtor(MatchEvent e, Long playerId) {
        return e.getActor() != null && playerId.equals(e.getActor().getId());
    }

    private boolean ehVitima(MatchEvent e, Long playerId) {
        return e.getVictim() != null && playerId.equals(e.getVictim().getId());
    }

    private boolean mesmoLado(MatchEvent e) {
        return e.getActorSide() != null && e.getVictimSide() != null
                && e.getActorSide() == e.getVictimSide();
    }

    private double media(List<Double> vs) {
        return vs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
