package com.countatic.core.service;

import com.countatic.core.entity.RankTier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compara o desempenho de um jogador com o de outros da <b>mesma faixa</b>.
 *
 * <p>O ponto central: comparar contra profissional não informa nada acionável,
 * e comparar contra uma média global mistura iniciante com veterano, ficando
 * errada para os dois. A referência útil é gente do seu próprio nível.</p>
 *
 * <p><b>A base é construída pelo próprio sistema.</b> Cada partida analisada
 * registra 10 desempenhos em {@code player_match_stats} — não só o do usuário.
 * Não há dependência de API externa nem de números publicados sem fonte.</p>
 *
 * <p><b>Honestidade estatística.</b> Abaixo de {@link #amostraMinima} desempenhos
 * na faixa, nenhuma comparação é devolvida. Um percentil calculado sobre 5
 * amostras é pior que não mostrar nada: parece preciso e não é.</p>
 */
@Slf4j
@Service
public class BaselineService {

    /**
     * Métricas comparáveis e a coluna correspondente.
     *
     * <p>Funciona também como <b>whitelist</b>: o nome da coluna é interpolado
     * em SQL nativo (não há como parametrizar identificadores), então só valores
     * desta tabela chegam à consulta. Entrada do usuário nunca é usada.</p>
     *
     * <p>Só entram métricas normalizadas por round ou expressas como razão —
     * absolutos não são comparáveis entre partidas de tamanhos diferentes.</p>
     */
    private static final Map<String, MetricaInfo> METRICAS = new LinkedHashMap<>();

    static {
        METRICAS.put("adr", new MetricaInfo("adr", "ADR", true));
        METRICAS.put("kdRatio", new MetricaInfo("kd_ratio", "K/D", true));
        METRICAS.put("headshotPercentage", new MetricaInfo("headshot_percentage", "Headshot %", true));
        METRICAS.put("killsPerRound", new MetricaInfo("kills_per_round", "Kills / round", true));
        // Uma das métricas em que MENOR é melhor — daí a flag existir.
        METRICAS.put("deathsPerRound", new MetricaInfo("deaths_per_round", "Mortes / round", false));
        METRICAS.put("tradeKillsPerRound", new MetricaInfo("trade_kills_per_round", "Trades / round", true));
        METRICAS.put("openingDuelWinRate", new MetricaInfo("opening_duel_win_rate", "Taxa 1º duelo", true));
        METRICAS.put("flashEfficiency", new MetricaInfo("flash_efficiency", "Eficiência de flash", true));
        METRICAS.put("utilityDamagePerRound", new MetricaInfo("utility_damage_per_round", "Dano util. / round", true));
        METRICAS.put("crosshairPlacementScore", new MetricaInfo("crosshair_placement_score", "Crosshair placement", true));

        // Posicionamento. Só entram as de direção inequívoca: o percentil
        // precisa saber para que lado olhar, e "distância média das mortes"
        // não tem resposta — morrer mais longe não é melhor nem pior.
        METRICAS.put("closeRangeWinRate", new MetricaInfo("close_range_win_rate", "Duelos curtos", true));
        METRICAS.put("longRangeWinRate", new MetricaInfo("long_range_win_rate", "Duelos longos", true));
        // Morrer menos na entrada é melhor — segunda métrica invertida.
        METRICAS.put("earlyDeathRate", new MetricaInfo("early_death_rate", "Mortes na entrada", false));
    }

    /** Nome da coluna, rótulo e se valores maiores são melhores. */
    private record MetricaInfo(String coluna, String rotulo, boolean maiorEhMelhor) {
    }

    @PersistenceContext
    private EntityManager em;

    private final int amostraMinima;

    public BaselineService(@Value("${countatic.baseline.min-sample:30}") int amostraMinima) {
        this.amostraMinima = amostraMinima;
    }

    /** Comparação de uma métrica contra a faixa. */
    @Data
    @Builder
    public static class Comparacao {
        private String metrica;
        private String rotulo;
        /** Valor do jogador nesta partida. */
        private Double valor;
        /** Média da faixa. */
        private Double media;
        /** % de desempenhos da faixa que este valor supera (0-100). */
        private Double percentil;
        /** Tamanho da amostra usada. */
        private long amostra;
        private boolean maiorEhMelhor;
        /** Frase pronta para exibição. */
        private String resumo;
    }

    /** Resultado completo da comparação de um jogador numa partida. */
    @Data
    @Builder
    public static class ResultadoComparacao {
        private RankTier faixa;
        private String faixaLabel;
        private long amostraTotal;
        private boolean amostraSuficiente;
        /** Explica por que não há comparação, quando for o caso. */
        private String aviso;
        private Map<String, Comparacao> metricas;
    }

    /**
     * Compara os valores de um jogador com a distribuição da sua faixa.
     *
     * @param faixa   faixa de comparação; {@code null} quando não há rating
     * @param valores métricas do jogador, na nomenclatura de {@link #METRICAS}
     */
    @Transactional(readOnly = true)
    public ResultadoComparacao comparar(RankTier faixa, Map<String, Double> valores) {
        if (faixa == null) {
            return ResultadoComparacao.builder()
                    .amostraSuficiente(false)
                    .amostraTotal(0)
                    .aviso("Sem CS Rating registrado para esta partida, "
                            + "então não há faixa de comparação.")
                    .metricas(Map.of())
                    .build();
        }

        long total = contarNaFaixa(faixa);

        if (total < amostraMinima) {
            return ResultadoComparacao.builder()
                    .faixa(faixa)
                    .faixaLabel(faixa.getLabel())
                    .amostraTotal(total)
                    .amostraSuficiente(false)
                    .aviso(String.format(
                            "Amostra insuficiente: %d de %d desempenhos necessários na faixa %s. "
                                    + "A base cresce a cada partida analisada — 10 desempenhos por partida.",
                            total, amostraMinima, faixa.getDisplayName()))
                    .metricas(Map.of())
                    .build();
        }

        Map<String, Comparacao> resultado = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entrada : valores.entrySet()) {
            MetricaInfo info = METRICAS.get(entrada.getKey());
            if (info == null || entrada.getValue() == null) continue;

            Comparacao c = compararMetrica(faixa, info, entrada.getKey(), entrada.getValue());
            if (c != null) resultado.put(entrada.getKey(), c);
        }

        return ResultadoComparacao.builder()
                .faixa(faixa)
                .faixaLabel(faixa.getLabel())
                .amostraTotal(total)
                .amostraSuficiente(true)
                .metricas(resultado)
                .build();
    }

    /** Métricas que o sistema sabe comparar. */
    public static Set<String> metricasSuportadas() {
        return METRICAS.keySet();
    }

    /**
     * Descrição pública de uma métrica comparável.
     *
     * <p>Existe para que outros serviços — o de tendência, por exemplo —
     * reaproveitem o rótulo e a direção sem redeclará-los. O nome da coluna
     * fica de fora de propósito: ele é detalhe de persistência e só faz
     * sentido dentro da whitelist do SQL nativo daqui.</p>
     */
    public record MetricaDescricao(String chave, String rotulo, boolean maiorEhMelhor) {
    }

    /** Descrição da métrica, ou vazio se ela não for comparável. */
    public static Optional<MetricaDescricao> descrever(String chave) {
        MetricaInfo info = METRICAS.get(chave);
        return info == null
                ? Optional.empty()
                : Optional.of(new MetricaDescricao(chave, info.rotulo(), info.maiorEhMelhor()));
    }

    /**
     * Média de uma métrica na faixa, ou {@code null} sem amostra suficiente.
     *
     * <p>Serve de linha de referência para o gráfico de evolução. Aplica a
     * <b>mesma guarda de amostra mínima</b> da comparação por percentil: uma
     * média sobre cinco partidas desenharia uma referência que parece
     * autoridade e não é.</p>
     *
     * <p>Devolve {@code null} — e não zero — quando não há amostra, pela mesma
     * razão que as métricas ausentes: zero seria lido como "a faixa tem média
     * zero".</p>
     */
    @Transactional(readOnly = true)
    public Double mediaDaFaixa(RankTier faixa, String chave) {
        MetricaInfo info = METRICAS.get(chave);
        if (faixa == null || info == null) {
            return null;
        }

        // Coluna vinda exclusivamente da whitelist, nunca do usuário.
        String sql = String.format("""
                SELECT COUNT(*) AS n, AVG(%1$s) AS media
                FROM player_match_stats
                WHERE rank_tier = :tier AND %1$s IS NOT NULL
                """, info.coluna());

        Object[] linha = (Object[]) em.createNativeQuery(sql)
                .setParameter("tier", faixa.name())
                .getSingleResult();

        return toLong(linha[0]) < amostraMinima ? null : round2(toDouble(linha[1]));
    }

    // ═══════════════════════════════════════════════════════════════════

    private Comparacao compararMetrica(RankTier faixa, MetricaInfo info, String chave, double valor) {
        // A coluna vem exclusivamente de METRICAS (whitelist), nunca do usuário.
        String sql = String.format("""
                SELECT COUNT(*)                                   AS n,
                       AVG(%1$s)                                  AS media,
                       SUM(CASE WHEN %1$s <= :valor THEN 1 ELSE 0 END) AS abaixo
                FROM player_match_stats
                WHERE rank_tier = :tier AND %1$s IS NOT NULL
                """, info.coluna());

        Object[] linha = (Object[]) em.createNativeQuery(sql)
                .setParameter("valor", valor)
                .setParameter("tier", faixa.name())
                .getSingleResult();

        long n = toLong(linha[0]);
        if (n < amostraMinima) {
            // A faixa tem amostra, mas esta métrica específica não.
            return null;
        }

        double media = toDouble(linha[1]);
        long abaixo = toLong(linha[2]);

        // Percentil bruto: quantos desempenhos este valor supera ou iguala.
        double percentil = (abaixo * 100.0) / n;

        // Em métricas onde MENOR é melhor (mortes por round), o percentil bruto
        // premia o pior desempenho. Inverter mantém a leitura "maior = melhor".
        if (!info.maiorEhMelhor()) {
            percentil = 100.0 - percentil;
        }

        return Comparacao.builder()
                .metrica(chave)
                .rotulo(info.rotulo())
                .valor(round2(valor))
                .media(round2(media))
                .percentil(round2(percentil))
                .amostra(n)
                .maiorEhMelhor(info.maiorEhMelhor())
                .resumo(montarResumo(info, valor, media, percentil, faixa))
                .build();
    }

    private String montarResumo(MetricaInfo info, double valor, double media,
                                 double percentil, RankTier faixa) {
        String comparativo;
        if (percentil >= 80) {
            comparativo = "entre os melhores";
        } else if (percentil >= 60) {
            comparativo = "acima da média";
        } else if (percentil >= 40) {
            comparativo = "na média";
        } else if (percentil >= 20) {
            comparativo = "abaixo da média";
        } else {
            comparativo = "bem abaixo da média";
        }

        return String.format("%s: %.1f — %s do %s (média %.1f).",
                info.rotulo(), valor, comparativo, faixa.getDisplayName(), media);
    }

    private long contarNaFaixa(RankTier faixa) {
        Object r = em.createNativeQuery(
                        "SELECT COUNT(*) FROM player_match_stats WHERE rank_tier = :tier")
                .setParameter("tier", faixa.name())
                .getSingleResult();
        return toLong(r);
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof BigDecimal b) return b.doubleValue();
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
