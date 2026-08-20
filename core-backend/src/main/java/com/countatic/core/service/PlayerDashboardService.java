package com.countatic.core.service;

import com.countatic.core.dto.stats.PlayerDashboardDTO;
import com.countatic.core.entity.Match;
import com.countatic.core.entity.Player;
import com.countatic.core.entity.PlayerMatchStats;
import com.countatic.core.entity.RankTier;
import com.countatic.core.entity.Team;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import com.countatic.core.repository.PlayerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Painel do jogador: como ele está, não como foi numa partida.
 *
 * <p>Lê de {@code player_match_stats}, a mesma origem do gráfico de evolução.
 * Recalcular pelas Strategies traria os eventos de vinte partidas numa única
 * requisição — a tabela existe exatamente para isso não acontecer.</p>
 */
@Slf4j
@Service
public class PlayerDashboardService {

    /** Janela padrão do painel. */
    public static final int PARTIDAS_PADRAO = 20;

    /** Teto: sem ele, um parâmetro grande varreria o histórico inteiro. */
    public static final int PARTIDAS_MAXIMO = 100;

    private final PlayerRepository playerRepository;
    private final PlayerMatchStatsRepository statsRepository;
    private final BaselineService baselineService;

    public PlayerDashboardService(PlayerRepository playerRepository,
                                  PlayerMatchStatsRepository statsRepository,
                                  BaselineService baselineService) {
        this.playerRepository = playerRepository;
        this.statsRepository = statsRepository;
        this.baselineService = baselineService;
    }

    /** Painel a partir do token público da URL. */
    @Transactional(readOnly = true)
    public Optional<PlayerDashboardDTO> porToken(String token, int limite) {
        return playerRepository.findByPublicToken(token).map(p -> montar(p, limite));
    }

    /** Painel a partir do SteamID64 — usado pela API, não pela página. */
    @Transactional(readOnly = true)
    public Optional<PlayerDashboardDTO> porSteamId(String steamId64, int limite) {
        return playerRepository.findBySteamId64(steamId64).map(p -> montar(p, limite));
    }

    // ═══════════════════════════════════════════════════════════════════

    private PlayerDashboardDTO montar(Player jogador, int limite) {
        int n = Math.max(1, Math.min(limite, PARTIDAS_MAXIMO));

        List<PlayerMatchStats> recentes =
                statsRepository.findRecentesComPartida(jogador.getSteamId64(), PageRequest.of(0, n));

        // A consulta vem do mais recente para o mais antigo — que é a ordem em
        // que a lista de partidas é lida. As médias não dependem de ordem.
        List<PlayerMatchStats> ordenado = new ArrayList<>(recentes);
        ordenado.sort(Comparator.comparing((PlayerMatchStats s) -> s.getMatch().getPlayedAt()).reversed());

        Map<String, Double> somas = new LinkedHashMap<>();
        Map<String, Integer> contagens = new LinkedHashMap<>();
        List<PlayerDashboardDTO.PartidaResumo> partidas = new ArrayList<>(ordenado.size());

        int vitorias = 0, derrotas = 0, desconhecidos = 0;
        RankTier faixa = null;
        Integer csRating = null;

        for (PlayerMatchStats s : ordenado) {
            Match m = s.getMatch();

            for (Map.Entry<String, Function<PlayerMatchStats, Double>> e
                    : PlayerTrendService.leitores().entrySet()) {
                Double v = e.getValue().apply(s);
                // Métrica ausente fica fora da média. Contá-la como zero
                // repetiria o defeito que a varredura de métricas removeu.
                if (v == null) continue;
                somas.merge(e.getKey(), v, Double::sum);
                contagens.merge(e.getKey(), 1, Integer::sum);
            }

            if (Boolean.TRUE.equals(s.getWon())) vitorias++;
            else if (Boolean.FALSE.equals(s.getWon())) derrotas++;
            else desconhecidos++;

            // A primeira da lista é a mais recente: é ela que representa o
            // jogador hoje.
            if (faixa == null && s.getRankTier() != null) faixa = s.getRankTier();
            if (csRating == null && s.getCsRating() != null) csRating = s.getCsRating();

            Integer proprio = null, adversario = null;
            if (s.getPlayerSide() != null && m.getScoreCT() != null && m.getScoreTR() != null) {
                boolean ct = s.getPlayerSide() == Team.CT;
                proprio = ct ? m.getScoreCT() : m.getScoreTR();
                adversario = ct ? m.getScoreTR() : m.getScoreCT();
            }

            partidas.add(PlayerDashboardDTO.PartidaResumo.builder()
                    .matchId(m.getId())
                    .publicToken(m.getPublicToken())
                    .mapName(m.getMapName())
                    .playedAt(m.getPlayedAt())
                    .won(s.getWon())
                    .scoreSelf(proprio)
                    .scoreEnemy(adversario)
                    .kills(s.getKills())
                    .deaths(s.getDeaths())
                    .adr(s.getAdr())
                    .kdRatio(s.getKdRatio())
                    .build());
        }

        Map<String, Double> medias = new LinkedHashMap<>();
        somas.forEach((k, soma) -> medias.put(k, round2(soma / contagens.get(k))));

        // Referência da faixa para as mesmas métricas. Vem vazia enquanto a
        // faixa não tiver amostra suficiente — o serviço de baseline já aplica
        // essa guarda, e repeti-la aqui só criaria um segundo limiar.
        Map<String, Double> mediasFaixa = new LinkedHashMap<>();
        if (faixa != null) {
            for (String chave : medias.keySet()) {
                Double m = baselineService.mediaDaFaixa(faixa, chave);
                if (m != null) mediasFaixa.put(chave, m);
            }
        }

        return PlayerDashboardDTO.builder()
                .porMapa(agregarPorMapa(ordenado))
                .steamId64(jogador.getSteamId64())
                .playerName(jogador.getDisplayName())
                .partidasAnalisadas(ordenado.size())
                .rankTier(faixa == null ? null : faixa.name())
                .rankTierLabel(faixa == null ? null : faixa.getLabel())
                .csRating(csRating)
                .vitorias(vitorias)
                .derrotas(derrotas)
                .resultadoDesconhecido(desconhecidos)
                .medias(medias)
                .mediasDaFaixa(mediasFaixa)
                .partidas(partidas)
                .build();
    }

    /**
     * Agrega os mesmos desempenhos por mapa.
     *
     * <p>Reaproveita as linhas já carregadas: uma consulta por mapa devolveria
     * exatamente estes registros de novo, agrupados de outro jeito.</p>
     *
     * <p>Devolve <b>vitórias e derrotas em números absolutos</b>, e não taxa de
     * vitória. Numa amostra de duas partidas, "50%" e "1V 1D" dizem a mesma
     * coisa — mas a porcentagem sugere uma precisão que não existe, e "100%"
     * vindo de uma partida só é pior ainda.</p>
     */
    private List<PlayerDashboardDTO.MapaResumo> agregarPorMapa(List<PlayerMatchStats> linhas) {
        Map<String, Acumulado> porMapa = new LinkedHashMap<>();

        for (PlayerMatchStats s : linhas) {
            String mapa = s.getMatch().getMapName();
            if (mapa == null || mapa.isBlank()) continue;

            Acumulado a = porMapa.computeIfAbsent(mapa, k -> new Acumulado());
            a.partidas++;

            if (Boolean.TRUE.equals(s.getWon())) a.vitorias++;
            else if (Boolean.FALSE.equals(s.getWon())) a.derrotas++;
            else a.desconhecidos++;

            // Métrica ausente fica fora da média, como em todo o resto.
            if (s.getKdRatio() != null) { a.somaKd += s.getKdRatio(); a.nKd++; }
            if (s.getAdr() != null) { a.somaAdr += s.getAdr(); a.nAdr++; }
        }

        List<PlayerDashboardDTO.MapaResumo> saida = new ArrayList<>(porMapa.size());
        porMapa.forEach((mapa, a) -> saida.add(PlayerDashboardDTO.MapaResumo.builder()
                .mapName(mapa)
                .partidas(a.partidas)
                .vitorias(a.vitorias)
                .derrotas(a.derrotas)
                .resultadoDesconhecido(a.desconhecidos)
                .kdRatio(a.nKd > 0 ? round2(a.somaKd / a.nKd) : null)
                .adr(a.nAdr > 0 ? round2(a.somaAdr / a.nAdr) : null)
                .build()));

        // Mais jogado primeiro: é o mapa que mais explica o desempenho geral.
        saida.sort(Comparator.comparingInt(PlayerDashboardDTO.MapaResumo::getPartidas).reversed()
                .thenComparing(PlayerDashboardDTO.MapaResumo::getMapName));

        return saida;
    }

    /** Contagens de um mapa antes de virarem médias. */
    private static final class Acumulado {
        int partidas, vitorias, derrotas, desconhecidos;
        double somaKd, somaAdr;
        int nKd, nAdr;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
