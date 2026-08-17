package com.countatic.core.service;

import com.countatic.core.dto.stats.TrendSeriesDTO;
import com.countatic.core.entity.Match;
import com.countatic.core.entity.PlayerMatchStats;
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
    }

    private final PlayerMatchStatsRepository statsRepository;

    public PlayerTrendService(PlayerMatchStatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    /** Lançada quando a métrica pedida não é uma das comparáveis. */
    public static class MetricaDesconhecidaException extends IllegalArgumentException {
        public MetricaDesconhecidaException(String chave) {
            super("Métrica desconhecida: " + chave);
        }
    }

    /**
     * Monta a série das últimas partidas do jogador.
     *
     * @param steamId64 jogador
     * @param metrica   chave da métrica; precisa estar em {@link BaselineService#metricasSuportadas()}
     * @param limite    quantas partidas; ajustado ao intervalo [1, {@value #LIMITE_MAXIMO}]
     */
    @Transactional(readOnly = true)
    public TrendSeriesDTO serie(String steamId64, String metrica, int limite) {
        var descricao = BaselineService.descrever(metrica)
                .orElseThrow(() -> new MetricaDesconhecidaException(metrica));

        Function<PlayerMatchStats, Double> ler = LEITORES.get(metrica);
        if (ler == null) {
            // Só acontece se alguém acrescentar uma métrica ao BaselineService
            // e esquecer deste mapa. Falhar alto é melhor que devolver uma
            // série vazia que parece "jogador sem histórico".
            throw new IllegalStateException(
                    "Métrica '" + metrica + "' é comparável mas não tem leitor em PlayerTrendService");
        }

        int n = Math.max(1, Math.min(limite, LIMITE_MAXIMO));

        List<PlayerMatchStats> recentes =
                statsRepository.findRecentesComPartida(steamId64, PageRequest.of(0, n));

        // A consulta vem do mais recente para o mais antigo, porque é assim que
        // se limita aos N últimos. O gráfico lê ao contrário.
        List<PlayerMatchStats> cronologico = new ArrayList<>(recentes);
        cronologico.sort(Comparator.comparing(s -> s.getMatch().getPlayedAt()));

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

            pontos.add(TrendSeriesDTO.Ponto.builder()
                    .matchId(m.getId())
                    .mapName(m.getMapName())
                    .playedAt(m.getPlayedAt())
                    .valor(valor)
                    .build());
        }

        return TrendSeriesDTO.builder()
                .steamId64(steamId64)
                .metric(metrica)
                .label(descricao.rotulo())
                .maiorEhMelhor(descricao.maiorEhMelhor())
                // Média só dos pontos medidos: incluir os ausentes como zero
                // afundaria a linha de referência.
                .media(comValor > 0 ? soma / comValor : null)
                .pontos(pontos)
                .build();
    }
}
