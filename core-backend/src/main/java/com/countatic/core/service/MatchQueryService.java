package com.countatic.core.service;

import com.countatic.core.award.AwardCalculatorService;
import com.countatic.core.dto.stats.AwardDTO;
import com.countatic.core.dto.stats.MatchDetailDTO;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.repository.RoundRepository;
import com.countatic.core.strategy.StatCalculationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Monta a visão de leitura de uma partida para a API e para a página de detalhes.
 *
 * <p>As métricas são recalculadas sob demanda a partir dos eventos persistidos,
 * em vez de armazenadas. É uma troca deliberada: o cálculo roda inteiramente em
 * memória sobre dados já carregados (milissegundos), e em troca qualquer ajuste
 * numa fórmula passa a valer imediatamente para todo o histórico — sem migração
 * nem reprocessamento das demos, que já expiraram no CDN da Valve.</p>
 */
@Slf4j
@Service
public class MatchQueryService {

    private final MatchRepository matchRepository;
    private final RoundRepository roundRepository;
    private final PlayerRepository playerRepository;
    private final BaselineService baselineService;
    private final List<StatCalculationStrategy> strategies;
    private final AwardCalculatorService awardCalculator;
    private final PersonalRecordService recordService;

    public MatchQueryService(MatchRepository matchRepository,
                             RoundRepository roundRepository,
                             PlayerRepository playerRepository,
                             BaselineService baselineService,
                             List<StatCalculationStrategy> strategies,
                             AwardCalculatorService awardCalculator,
                             PersonalRecordService recordService) {
        this.matchRepository = matchRepository;
        this.roundRepository = roundRepository;
        this.playerRepository = playerRepository;
        this.baselineService = baselineService;
        this.strategies = strategies;
        this.awardCalculator = awardCalculator;
        this.recordService = recordService;
    }

    @Transactional(readOnly = true)
    public Optional<MatchDetailDTO> findByPublicToken(String token) {
        return matchRepository.findByPublicToken(token).map(m -> buildDetail(m.getId()));
    }

    @Transactional(readOnly = true)
    public Optional<MatchDetailDTO> findById(Long id) {
        if (!matchRepository.existsById(id)) {
            return Optional.empty();
        }
        return Optional.of(buildDetail(id));
    }

    @Transactional(readOnly = true)
    public List<MatchDetailDTO> findRecent(int limit) {
        return matchRepository.findAllByOrderByPlayedAtDesc().stream()
                .limit(limit)
                .map(m -> MatchDetailDTO.builder()
                        .matchId(m.getId())
                        .publicToken(m.getPublicToken())
                        .mapName(m.getMapName())
                        .scoreCT(m.getScoreCT())
                        .scoreTR(m.getScoreTR())
                        .totalRounds(m.getTotalRounds())
                        .durationSeconds(m.getDurationSeconds())
                        .tickRate(m.getTickRate())
                        .playedAt(m.getPlayedAt())
                        .status(m.getStatus() == null ? null : m.getStatus().name())
                        // players omitido de propósito: a listagem não precisa
                        // carregar milhares de eventos por partida.
                        .build())
                .toList();
    }

    /**
     * Carrega a partida inteira e calcula as métricas de todos os jogadores.
     */
    private MatchDetailDTO buildDetail(Long matchId) {
        Match match = matchRepository.findByIdWithRounds(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada: " + matchId));

        // Popula eventos e jogadores no mesmo contexto de persistência.
        // Sem isto, cada acesso a round.getEvents() dispararia uma consulta.
        roundRepository.findWithEventsByMatchId(matchId);

        Map<String, MatchDetailDTO.PlayerRow> rows = new LinkedHashMap<>();
        Map<String, Player> playersById = new LinkedHashMap<>();

        // ─── Contagens básicas a partir dos eventos ──────────────────
        for (Round round : match.getRounds()) {
            for (MatchEvent event : round.getEvents()) {
                registerPlayer(playersById, rows, event.getActor());
                registerPlayer(playersById, rows, event.getVictim());
                registerPlayer(playersById, rows, event.getAssister());

                switch (event.getEventType()) {
                    case KILL -> {
                        if (event.getActor() != null) {
                            var row = rows.get(event.getActor().getSteamId64());
                            row.setKills(row.getKills() + 1);
                            if (Boolean.TRUE.equals(event.getIsHeadshot())) {
                                row.setHeadshots(row.getHeadshots() + 1);
                            }
                        }
                        // Mortes derivam do KILL: o parser não emite DEATH
                        // separado, pois duplicaria as linhas sem acrescentar nada.
                        if (event.getVictim() != null) {
                            var row = rows.get(event.getVictim().getSteamId64());
                            row.setDeaths(row.getDeaths() + 1);
                        }
                        if (event.getAssister() != null) {
                            var row = rows.get(event.getAssister().getSteamId64());
                            row.setAssists(row.getAssists() + 1);
                        }
                    }
                    case DAMAGE -> {
                        if (event.getActor() != null && event.getDamageAmount() != null) {
                            var row = rows.get(event.getActor().getSteamId64());
                            row.setDamage(row.getDamage() + event.getDamageAmount());
                        }
                    }
                    default -> { /* demais eventos alimentam só as strategies */ }
                }
            }
        }

        // ─── Métricas das strategies ─────────────────────────────────
        for (Map.Entry<String, Player> entry : playersById.entrySet()) {
            MatchDetailDTO.PlayerRow row = rows.get(entry.getKey());

            for (StatCalculationStrategy strategy : strategies) {
                try {
                    PlayerStatResult result = strategy.calculate(match, entry.getValue());
                    if (result == null) continue;

                    if (result.getMetrics() != null && !result.getMetrics().isEmpty()) {
                        row.getMetrics().put(result.getCategory(), result.getMetrics());
                    }
                    if (result.getInsights() != null && !result.getInsights().isEmpty()) {
                        row.getInsights().put(result.getCategory(), result.getInsights());
                    }
                } catch (Exception e) {
                    // Uma strategy quebrada não pode derrubar a página inteira.
                    log.warn("Strategy '{}' falhou para {} na partida {}: {}",
                            strategy.getCategory(), entry.getKey(), matchId, e.getMessage());
                }
            }
        }

        // Títulos: depois das strategies, porque uma regra pode cruzar
        // categorias — "Arquiteto" olha flash (Utility) e dano de granada.
        int roundsDaPartida = match.getTotalRounds() == null ? 0 : match.getTotalRounds();
        Map<String, Map<String, Double>> achatadas = new LinkedHashMap<>();

        for (MatchDetailDTO.PlayerRow linha : rows.values()) {
            Map<String, Double> todas = new LinkedHashMap<>();
            linha.getMetrics().values().forEach(todas::putAll);
            achatadas.put(linha.getSteamId64(), todas);

            awardCalculator.calcular(new AwardCalculatorService.AwardContext(
                            linha.getKills(), linha.getDeaths(), linha.getAssists(),
                            roundsDaPartida, todas))
                    .map(AwardDTO::de)
                    .ifPresent(linha::setAward);
        }

        // Recordes pessoais no mapa. Uma consulta para todos os jogadores,
        // reaproveitando o mesmo mapa achatado dos títulos.
        recordService.recordes(match.getMapName(), match.getId(), achatadas)
                .forEach((steamId, chaves) -> {
                    MatchDetailDTO.PlayerRow linha = rows.get(steamId);
                    if (linha != null) linha.setPersonalBests(chaves);
                });

        List<MatchDetailDTO.PlayerRow> ordered = new ArrayList<>(rows.values());
        ordered.sort(Comparator.comparingInt(MatchDetailDTO.PlayerRow::getKills).reversed());

        // ─── Comparação com a faixa ──────────────────────────────────
        // Só para quem cadastrou a partida: é o único jogador de quem
        // conhecemos o CS Rating, e comparar os outros contra uma faixa que
        // não sabemos ser a deles seria inventar dado.
        anexarComparacao(match, ordered);

        return MatchDetailDTO.builder()
                .matchId(match.getId())
                .publicToken(match.getPublicToken())
                .mapName(match.getMapName())
                .scoreCT(match.getScoreCT())
                .scoreTR(match.getScoreTR())
                .totalRounds(match.getTotalRounds())
                .durationSeconds(match.getDurationSeconds())
                .tickRate(match.getTickRate())
                .playedAt(match.getPlayedAt())
                .status(match.getStatus() == null ? null : match.getStatus().name())
                .csRating(match.getCsRating())
                .rankTier(match.getRankTier() == null ? null : match.getRankTier().name())
                .rankTierLabel(match.getRankTier() == null ? null : match.getRankTier().getLabel())
                .ownerToken(tokenDoDono(ordered))
                .players(ordered)
                .build();
    }

    /**
     * Anexa a comparação por faixa aos jogadores cadastrados no sistema.
     *
     * <p>Um jogador só recebe comparação se estiver cadastrado — é dele que
     * conhecemos o CS Rating. Atribuir a faixa da partida aos outros nove
     * serviria para alimentar a base (o matchmaking os pareia), mas exibir a
     * eles um percentil de uma faixa que talvez não seja a deles seria
     * apresentar suposição como fato.</p>
     */
    /**
     * Token do painel do primeiro participante cadastrado.
     *
     * <p>Separado de {@link #anexarComparacao} porque aquele só roda quando a
     * partida tem faixa definida, e o link para o perfil deve existir de
     * qualquer forma.</p>
     */
    private String tokenDoDono(List<MatchDetailDTO.PlayerRow> linhas) {
        for (MatchDetailDTO.PlayerRow linha : linhas) {
            var dono = playerRepository.findBySteamId64(linha.getSteamId64())
                    .filter(p -> p.getAuthCode() != null)
                    .map(com.countatic.core.entity.Player::getPublicToken)
                    .orElse(null);
            if (dono != null) return dono;
        }
        return null;
    }

    private void anexarComparacao(Match match, List<MatchDetailDTO.PlayerRow> linhas) {
        if (match.getRankTier() == null) return;

        for (MatchDetailDTO.PlayerRow linha : linhas) {
            boolean cadastrado = playerRepository.findBySteamId64(linha.getSteamId64())
                    .map(p -> p.getAuthCode() != null)
                    .orElse(false);
            if (!cadastrado) continue;

            Map<String, Double> valores = new LinkedHashMap<>();
            for (Map<String, Double> categoria : linha.getMetrics().values()) {
                valores.putAll(categoria);
            }
            // tradeKills absoluto não compara entre partidas de tamanhos diferentes.
            Double trades = valores.get("tradeKills");
            if (trades != null && match.getTotalRounds() != null && match.getTotalRounds() > 0) {
                valores.put("tradeKillsPerRound", trades / match.getTotalRounds());
            }

            linha.setBaseline(baselineService.comparar(match.getRankTier(), valores));
        }
    }

    private void registerPlayer(Map<String, Player> playersById,
                                 Map<String, MatchDetailDTO.PlayerRow> rows,
                                 Player player) {
        if (player == null || player.getSteamId64() == null) return;

        playersById.computeIfAbsent(player.getSteamId64(), k -> player);
        rows.computeIfAbsent(player.getSteamId64(), k -> MatchDetailDTO.PlayerRow.builder()
                .steamId64(player.getSteamId64())
                .playerName(player.getDisplayName())
                .metrics(new LinkedHashMap<>())
                .insights(new LinkedHashMap<>())
                .build());
    }
}
