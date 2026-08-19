package com.countatic.core.service;

import com.countatic.core.dto.stats.TrendSeriesDTO;
import com.countatic.core.entity.Match;
import com.countatic.core.entity.PlayerMatchStats;
import com.countatic.core.entity.RankTier;
import com.countatic.core.entity.Team;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Série histórica de uma métrica para um jogador.
 *
 * <p><b>Por que um serviço próprio.</b> O {@link MatchQueryService} responde
 * pela visão de <i>uma partida com vários jogadores</i>; aqui o eixo é o
 * oposto — <i>um jogador ao longo de várias partidas</i>. Ele também recalcula
 * tudo a partir dos eventos, o que é inviável para dez partidas numa
 * requisição, e nem injeta o repositório de desempenhos.</p>
 */
@Slf4j
@Service
public class PlayerTrendService {

    /**
     * Teto de partidas por consulta.
     *
     * <p>O parâmetro da requisição é limitado a este valor: sem teto, um
     * {@code limit=100000} carregaria o histórico inteiro do jogador com um
     * join por linha.</p>
     */
    public static final int LIMITE_MAXIMO = 50;

    public static final int LIMITE_PADRAO = 10;

    /**
     * Como ler cada métrica de uma linha de {@code player_match_stats}.
     *
     * <p>As chaves são exatamente as de {@link BaselineService#metricasSuportadas()}
     * — é de lá que vem a validação e o rótulo. Este mapa acrescenta só o que
     * o {@code BaselineService} não tem: o acesso ao campo, que lá é feito por
     * nome de coluna em SQL nativo.</p>
     */
    private static final Map<String, Function<PlayerMatchStats, Double>> LEITORES = new LinkedHashMap<>();

    /**
     * Como ler cada métrica comparável de uma linha de desempenho.
     *
     * <p>Exposto para o painel do jogador consumir o mesmo mapeamento. Duplicá-lo
     * lá significaria dois lugares para atualizar quando uma métrica entrasse
     * na whitelist do {@link BaselineService} — e o que ficasse para trás
     * falharia em silêncio, devolvendo média vazia.</p>
     */
    public static Map<String, Function<PlayerMatchStats, Double>> leitores() {
        return Map.copyOf(LEITORES);
    }

    static {
        LEITORES.put("adr", PlayerMatchStats::getAdr);
        LEITORES.put("kdRatio", PlayerMatchStats::getKdRatio);
        LEITORES.put("headshotPercentage", PlayerMatchStats::getHeadshotPercentage);
        LEITORES.put("killsPerRound", PlayerMatchStats::getKillsPerRound);
        LEITORES.put("deathsPerRound", PlayerMatchStats::getDeathsPerRound);
        LEITORES.put("tradeKillsPerRound", PlayerMatchStats::getTradeKillsPerRound);
        LEITORES.put("openingDuelWinRate", PlayerMatchStats::getOpeningDuelWinRate);
        LEITORES.put("flashEfficiency", PlayerMatchStats::getFlashEfficiency);
        LEITORES.put("utilityDamagePerRound", PlayerMatchStats::getUtilityDamagePerRound);
        LEITORES.put("crosshairPlacementScore", PlayerMatchStats::getCrosshairPlacementScore);
        LEITORES.put("closeRangeWinRate", PlayerMatchStats::getCloseRangeWinRate);
        LEITORES.put("longRangeWinRate", PlayerMatchStats::getLongRangeWinRate);
        LEITORES.put("earlyDeathRate", PlayerMatchStats::getEarlyDeathRate);
    }

    private final PlayerMatchStatsRepository statsRepository;
    private final BaselineService baselineService;

    public PlayerTrendService(PlayerMatchStatsRepository statsRepository,
                              BaselineService baselineService) {
        this.statsRepository = statsRepository;
        this.baselineService = baselineService;
    }

    /** Lançada quando a métrica pedida não é uma das comparáveis. */
    public static class MetricaDesconhecidaException extends IllegalArgumentException {
        public MetricaDesconhecidaException(String chave) {
            super("Métrica desconhecida: " + chave);
        }
    }

    /**
     * Monta a série das últimas partidas do jogador para uma métrica.
     *
     * @param steamId64 jogador
     * @param metrica   chave; precisa estar em {@link BaselineService#metricasSuportadas()}
     * @param limite    quantas partidas; ajustado ao intervalo [1, {@value #LIMITE_MAXIMO}]
     */
    @Transactional(readOnly = true)
    public TrendSeriesDTO serie(String steamId64, String metrica, int limite) {
        return series(steamId64, List.of(metrica), limite).get(0);
    }

    /**
     * Várias métricas de uma vez, a partir da <b>mesma leitura</b> do banco.
     *
     * <p>Existe porque as sparklines dos cards pedem uma série por métrica, e
     * uma consulta por card significaria dez consultas idênticas variando só a
     * coluna lida — sendo que as linhas necessárias são exatamente as mesmas.
     * Aqui as linhas são buscadas uma vez e cada métrica é extraída delas.</p>
     */
    @Transactional(readOnly = true)
    public List<TrendSeriesDTO> series(String steamId64, List<String> metricas, int limite) {
        if (metricas == null || metricas.isEmpty()) {
            throw new MetricaDesconhecidaException("(nenhuma)");
        }
        // Valida tudo antes de consultar: devolver metade das séries e estourar
        // no meio deixaria o cliente sem saber o que veio.
        for (String metrica : metricas) {
            BaselineService.descrever(metrica)
                    .orElseThrow(() -> new MetricaDesconhecidaException(metrica));
            if (!LEITORES.containsKey(metrica)) {
                // Só acontece se alguém acrescentar uma métrica ao BaselineService
                // e esquecer deste mapa. Falhar alto é melhor que devolver uma
                // série vazia que parece "jogador sem histórico".
                throw new IllegalStateException(
                        "Métrica '" + metrica + "' é comparável mas não tem leitor em PlayerTrendService");
            }
        }

        int n = Math.max(1, Math.min(limite, LIMITE_MAXIMO));

        List<PlayerMatchStats> recentes =
                statsRepository.findRecentesComPartida(steamId64, PageRequest.of(0, n));

        // A consulta vem do mais recente para o mais antigo, porque é assim que
        // se limita aos N últimos. O gráfico lê ao contrário.
        List<PlayerMatchStats> cronologico = new ArrayList<>(recentes);
        cronologico.sort(Comparator.comparing(s -> s.getMatch().getPlayedAt()));

        // A faixa da partida mais recente é a que representa o jogador hoje.
        RankTier faixa = null;
        for (PlayerMatchStats s : cronologico) {
            if (s.getRankTier() != null) faixa = s.getRankTier();
        }

        List<TrendSeriesDTO> saida = new ArrayList<>(metricas.size());
        for (String metrica : metricas) {
            saida.add(montar(steamId64, metrica, cronologico, faixa));
        }
        return saida;
    }

    // ═══════════════════════════════════════════════════════════════════

    private TrendSeriesDTO montar(String steamId64, String metrica,
                                  List<PlayerMatchStats> cronologico, RankTier faixa) {
        var descricao = BaselineService.descrever(metrica).orElseThrow();
        Function<PlayerMatchStats, Double> ler = LEITORES.get(metrica);

        List<TrendSeriesDTO.Ponto> pontos = new ArrayList<>(cronologico.size());
        double soma = 0;
        int comValor = 0;

        for (PlayerMatchStats s : cronologico) {
            Match m = s.getMatch();
            Double valor = ler.apply(s);

            if (valor != null) {
                soma += valor;
                comValor++;
            }

            // "13-9" não diz nada sem saber de que lado o jogador estava.
            // Orientar aqui evita que a interface repita essa lógica.
            Integer proprio = null, adversario = null;
            if (s.getPlayerSide() != null && m.getScoreCT() != null && m.getScoreTR() != null) {
                boolean ct = s.getPlayerSide() == Team.CT;
                proprio = ct ? m.getScoreCT() : m.getScoreTR();
                adversario = ct ? m.getScoreTR() : m.getScoreCT();
            }

            pontos.add(TrendSeriesDTO.Ponto.builder()
                    .matchId(m.getId())
                    .mapName(m.getMapName())
                    .playedAt(m.getPlayedAt())
                    .valor(valor)
                    .won(s.getWon())
                    .scoreSelf(proprio)
                    .scoreEnemy(adversario)
                    .build());
        }

        // Segunda referência do gráfico. Reaproveita o mesmo agregado que
        // alimenta a comparação por percentil, inclusive a guarda de amostra
        // mínima — se ela não passar, a linha simplesmente não é desenhada.
        Double mediaFaixa = faixa == null ? null : baselineService.mediaDaFaixa(faixa, metrica);

        return TrendSeriesDTO.builder()
                .steamId64(steamId64)
                .metric(metrica)
                .label(descricao.rotulo())
                .maiorEhMelhor(descricao.maiorEhMelhor())
                // Média só dos pontos medidos: incluir os ausentes como zero
                // afundaria a linha de referência.
                .media(comValor > 0 ? soma / comValor : null)
                .mediaDaFaixa(mediaFaixa)
                .faixaLabel(mediaFaixa == null || faixa == null ? null : faixa.getLabel())
                .faixaTier(mediaFaixa == null || faixa == null ? null : faixa.name())
                .pontos(pontos)
                .build();
    }
}
