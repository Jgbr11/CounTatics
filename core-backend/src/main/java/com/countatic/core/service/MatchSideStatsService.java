package com.countatic.core.service;

import com.countatic.core.dto.stats.Insight;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.dto.stats.SideStatsDTO;
import com.countatic.core.entity.Match;
import com.countatic.core.entity.Player;
import com.countatic.core.entity.Team;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.repository.RoundRepository;
import com.countatic.core.strategy.StatCalculationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Desempenho de um jogador separado por lado (CT e TR) numa partida.
 *
 * <p>Reaproveita as Strategies <b>sem alterá-las</b>: em vez de ensiná-las a
 * filtrar por lado, monta-se com {@link MatchSideView} uma visão da partida
 * contendo só os rounds daquele lado e roda-se o cálculo normal sobre ela.
 * A alternativa — um parâmetro de lado no {@code calculate} — espalharia a
 * mesma ramificação pelas quatro Strategies e por toda futura.</p>
 *
 * <p><b>Sob demanda de propósito.</b> Isto custa duas passadas extras de todas
 * as Strategies. Fazê-lo dentro da montagem da página multiplicaria por três o
 * trabalho dos dez jogadores em toda visita, para uma informação que só
 * interessa quando alguém abre o comparativo.</p>
 */
@Slf4j
@Service
public class MatchSideStatsService {

    private final MatchRepository matchRepository;
    private final RoundRepository roundRepository;
    private final PlayerRepository playerRepository;
    private final List<StatCalculationStrategy> strategies;

    public MatchSideStatsService(MatchRepository matchRepository,
                                 RoundRepository roundRepository,
                                 PlayerRepository playerRepository,
                                 List<StatCalculationStrategy> strategies) {
        this.matchRepository = matchRepository;
        this.roundRepository = roundRepository;
        this.playerRepository = playerRepository;
        this.strategies = strategies;
    }

    @Transactional(readOnly = true)
    public Optional<SideStatsDTO> calcular(Long matchId, String steamId64) {
        Optional<Match> partida = matchRepository.findByIdWithRounds(matchId);
        Optional<Player> jogador = playerRepository.findBySteamId64(steamId64);

        if (partida.isEmpty() || jogador.isEmpty()) {
            return Optional.empty();
        }

        // Popula eventos no mesmo contexto de persistência. Sem isto, cada
        // acesso a round.getEvents() dispararia uma consulta — e aqui os
        // eventos são percorridos duas vezes, uma por lado.
        roundRepository.findWithEventsByMatchId(matchId);

        Match m = partida.get();
        Player p = jogador.get();

        return Optional.of(SideStatsDTO.builder()
                .matchId(matchId)
                .steamId64(steamId64)
                .playerName(p.getDisplayName())
                .ct(calcularLado(m, p, Team.CT))
                .tr(calcularLado(m, p, Team.TR))
                .build());
    }

    private SideStatsDTO.Lado calcularLado(Match original, Player jogador, Team lado) {
        Match recorte = MatchSideView.recortar(original, jogador, lado);

        Map<String, Map<String, Double>> metrics = new LinkedHashMap<>();
        Map<String, Map<String, Insight>> insights = new LinkedHashMap<>();

        // Jogador que só atuou de um lado devolve o outro vazio — resposta
        // legítima, não erro.
        if (!recorte.getRounds().isEmpty()) {
            for (StatCalculationStrategy strategy : strategies) {
                try {
                    PlayerStatResult r = strategy.calculate(recorte, jogador);
                    if (r == null) continue;

                    if (r.getMetrics() != null && !r.getMetrics().isEmpty()) {
                        metrics.put(r.getCategory(), r.getMetrics());
                    }
                    if (r.getInsights() != null && !r.getInsights().isEmpty()) {
                        insights.put(r.getCategory(), r.getInsights());
                    }
                } catch (Exception e) {
                    // Uma strategy quebrada não pode derrubar o comparativo
                    // inteiro — mesmo tratamento da montagem da página.
                    log.warn("Strategy '{}' falhou no lado {} da partida {}: {}",
                            strategy.getCategory(), lado, original.getId(), e.getMessage());
                }
            }
        }

        return SideStatsDTO.Lado.builder()
                .roundsJogados(recorte.getRounds().size())
                .metrics(metrics)
                .insights(insights)
                .build();
    }
}
