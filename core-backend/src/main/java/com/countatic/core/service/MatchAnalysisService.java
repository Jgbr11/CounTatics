package com.countatic.core.service;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.countatic.core.dto.parser.ParsedEventDTO;
import com.countatic.core.dto.parser.ParsedRoundDTO;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.repository.RoundRepository;
import com.countatic.core.strategy.StatCalculationStrategy;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serviço orquestrador para análise completa de partidas de CS2.
 *
 * <p>Este serviço é o <b>ponto central de coordenação</b> entre a ingestão de dados
 * do Demo Parser (Go) e a geração de relatórios para o Steam Bot (Node.js).
 * Ele executa três responsabilidades em sequência:</p>
 *
 * <ol>
 *   <li><b>Conversão:</b> Transforma os DTOs do parser ({@link ParsedDemoDTO})
 *       em entidades JPA ({@link Match}, {@link Round}, {@link MatchEvent}),
 *       resolvendo referências de jogadores por SteamID64.</li>
 *   <li><b>Persistência:</b> Salva a árvore completa de entidades no MySQL
 *       via cascade em uma transação única.</li>
 *   <li><b>Cálculo:</b> Aplica todas as {@link StatCalculationStrategy} registradas
 *       (injetadas automaticamente pelo Spring) para cada jogador participante.</li>
 * </ol>
 *
 * <p>O resultado final é um {@link MatchAnalysisResult} contendo todos os insights
 * por jogador, pronto para ser enviado ao Steam Bot via webhook.</p>
 *
 * @see StatCalculationStrategy
 * @see com.countatic.core.strategy.impl.AimStatStrategy
 * @see com.countatic.core.strategy.impl.UtilityStatStrategy
 */
@Slf4j
@Service
public class MatchAnalysisService {

    private final MatchRepository matchRepository;
    private final RoundRepository roundRepository;
    private final PlayerRepository playerRepository;
    private final PlayerMatchStatsRepository playerMatchStatsRepository;
    private final List<StatCalculationStrategy> strategies;

    /**
     * Construtor com injeção de dependências.
     *
     * <p>O Spring injeta automaticamente todas as implementações de
     * {@link StatCalculationStrategy} registradas como {@code @Component}.
     * Isso garante o princípio Open/Closed: novas strategies são adicionadas
     * criando novas classes, sem modificar este serviço.</p>
     *
     * @param matchRepository  repositório de partidas
     * @param playerRepository repositório de jogadores
     * @param strategies       lista de todas as strategies de cálculo registradas
     */
    public MatchAnalysisService(MatchRepository matchRepository,
                                 RoundRepository roundRepository,
                                 PlayerRepository playerRepository,
                                 PlayerMatchStatsRepository playerMatchStatsRepository,
                                 List<StatCalculationStrategy> strategies) {
        this.matchRepository = matchRepository;
        this.roundRepository = roundRepository;
        this.playerRepository = playerRepository;
        this.playerMatchStatsRepository = playerMatchStatsRepository;
        this.strategies = strategies;

        log.info("MatchAnalysisService inicializado com {} strategies: {}",
                strategies.size(),
                strategies.stream()
                        .map(StatCalculationStrategy::getCategory)
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Processa uma demo completa: converte DTOs em entidades, persiste e calcula métricas.
     *
     * <p>Fluxo:</p>
     * <pre>
     * ParsedDemoDTO (JSON do Go)
     *   ↓ convertToEntities()
     * Match → Rounds → MatchEvents (entidades JPA)
     *   ↓ matchRepository.save()
     * MySQL (persistido)
     *   ↓ applyStrategies()
     * MatchAnalysisResult (insights por jogador)
     * </pre>
     *
     * @param demoFileName nome original do arquivo .dem
     * @param demoFileHash hash SHA-256 do arquivo .dem para deduplicação
     * @param parsedDemo   DTO com os dados extraídos pelo Demo Parser
     * @return resultado da análise com métricas e insights por jogador
     * @throws IllegalStateException se a demo já foi processada (hash duplicado)
     */
    @Transactional
    public MatchAnalysisResult processDemo(String demoFileName,
                                            String demoFileHash,
                                            ParsedDemoDTO parsedDemo) {
        return processDemo(demoFileName, demoFileHash, parsedDemo, null);
    }

    /**
     * Igual a {@link #processDemo(String, String, ParsedDemoDTO)}, mas permite
     * sobrescrever a data da partida.
     *
     * <p>O parser Go não tem como saber quando a partida aconteceu — o header
     * de demos Source 2 não carrega timestamp — então ele preenche com a hora
     * do parsing. Quando o Game Coordinator informa o {@code matchtime} real,
     * ele é passado aqui e tem prioridade.</p>
     *
     * @param playedAtOverride data real da partida, ou {@code null} para usar a do parser
     */
    @Transactional
    public MatchAnalysisResult processDemo(String demoFileName,
                                            String demoFileHash,
                                            ParsedDemoDTO parsedDemo,
                                            Instant playedAtOverride) {
        return processDemo(demoFileName, demoFileHash, parsedDemo, playedAtOverride, null);
    }

    /**
     * Igual às demais sobrecargas, mas registra também o CS Rating da partida.
     *
     * <p>O rating define a faixa de comparação: sem ele, os desempenhos são
     * salvos mas ficam fora de qualquer base de comparação — o que é correto,
     * já que não se sabe contra quem comparar.</p>
     *
     * @param csRating CS Rating (Premier) de quem cadastrou a partida
     */
    @Transactional
    public MatchAnalysisResult processDemo(String demoFileName,
                                            String demoFileHash,
                                            ParsedDemoDTO parsedDemo,
                                            Instant playedAtOverride,
                                            Integer csRating) {

        log.info("Iniciando processamento da demo: {} (hash: {})", demoFileName, demoFileHash);

        // ─── 1. Verificar duplicata ───────────────────────────────────────
        if (matchRepository.existsByDemoFileHash(demoFileHash)) {
            throw new IllegalStateException(
                    "Demo já processada anteriormente. Hash: " + demoFileHash);
        }

        // ─── 2. Converter DTOs em entidades ──────────────────────────────
        // O cache de jogadores é LOCAL a esta chamada. Como campo de instância
        // num singleton do Spring, ele nunca era limpo: a segunda demo reusava
        // entidades já desanexadas do contexto de persistência da primeira,
        // além de não ser thread-safe.
        Map<String, Player> playerCache = new HashMap<>();
        Match match = convertToMatch(demoFileName, demoFileHash, parsedDemo, playedAtOverride, playerCache);
        log.debug("Match convertida: mapa={}, rounds={}", match.getMapName(), match.getTotalRounds());

        // ─── 3. Persistir no banco ────────────────────────────────────────
        match.setStatus(MatchStatus.PROCESSING);
        match.setCsRating(csRating);
        match.setRankTier(RankTier.fromRating(csRating));
        Match savedMatch = matchRepository.save(match);
        log.debug("Match salva com ID={} (rating={}, faixa={})",
                savedMatch.getId(), csRating, savedMatch.getRankTier());

        // ─── 4. Aplicar strategies e calcular métricas ────────────────────
        Set<Player> participants = extractParticipants(savedMatch);
        log.debug("Jogadores encontrados na partida: {}", participants.size());

        List<PlayerStatResult> allStats = applyStrategies(savedMatch, participants);

        // ─── 5. Registrar os desempenhos para a base de comparação ────────
        // Cada partida rende 10 linhas — uma por jogador —, então a base de
        // referência cresce dez vezes mais rápido que o número de partidas.
        persistirDesempenhos(savedMatch, participants, allStats);

        // ─── 6. Marcar como concluída ─────────────────────────────────────
        savedMatch.setStatus(MatchStatus.COMPLETED);
        matchRepository.save(savedMatch);

        log.info("Processamento concluído para match ID={}. {} métricas geradas para {} jogadores.",
                savedMatch.getId(), allStats.size(), participants.size());

        return MatchAnalysisResult.builder()
                .matchId(savedMatch.getId())
                .mapName(savedMatch.getMapName())
                .finalScore(savedMatch.getScoreCT() + "-" + savedMatch.getScoreTR())
                .playerStats(allStats)
                .build();
    }

    /**
     * Recalcula e regrava os desempenhos de uma partida já analisada.
     *
     * <p>Serve a dois propósitos:</p>
     * <ol>
     *   <li><b>Backfill:</b> partidas analisadas antes de existir a captura de
     *       CS Rating ficaram sem faixa e, portanto, fora da base de comparação.
     *       Cada uma delas guarda 10 desempenhos que só precisavam ser lidos.</li>
     *   <li><b>Mudança de fórmula:</b> ajustar uma métrica exige reprocessar o
     *       histórico. Como os eventos estão persistidos, isso não depende da
     *       demo original — que expira no CDN da Valve em ~2 semanas.</li>
     * </ol>
     *
     * @param csRating rating a atribuir; {@code null} preserva o que já existe
     * @return quantidade de desempenhos regravados
     */
    @Transactional
    public int recomputePlayerStats(Long matchId, Integer csRating) {
        Match match = matchRepository.findByIdWithRounds(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada: " + matchId));

        // Materializa eventos e jogadores no mesmo contexto de persistência.
        roundRepository.findWithEventsByMatchId(matchId);

        if (csRating != null) {
            match.setCsRating(csRating);
            match.setRankTier(RankTier.fromRating(csRating));
            matchRepository.save(match);
        }

        // Apaga antes de regravar: a constraint (match, player) é única, e um
        // recompute tem que substituir, não acumular.
        playerMatchStatsRepository.deleteByMatchId(matchId);
        playerMatchStatsRepository.flush();

        Set<Player> participants = extractParticipants(match);
        List<PlayerStatResult> allStats = applyStrategies(match, participants);
        persistirDesempenhos(match, participants, allStats);

        log.info("Desempenhos recomputados para a partida {} ({} jogadores, faixa {}).",
                matchId, participants.size(), match.getRankTier());

        return participants.size();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  REGISTRO DOS DESEMPENHOS (base de comparação)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Consolida as métricas das várias strategies numa linha por jogador.
     *
     * <p>As strategies devolvem um resultado por categoria (Aim, Utility,
     * Impacto); aqui elas são achatadas num único registro por jogador, que é o
     * formato que as consultas de percentil conseguem agregar.</p>
     */
    private void persistirDesempenhos(Match match, Set<Player> participants,
                                       List<PlayerStatResult> allStats) {
        int rounds = match.getTotalRounds() == null ? 0 : match.getTotalRounds();

        // Agrupa todas as métricas por jogador, independentemente da categoria.
        Map<String, Map<String, Double>> porJogador = new HashMap<>();
        for (PlayerStatResult r : allStats) {
            if (r == null || r.getSteamId64() == null || r.getMetrics() == null) continue;
            porJogador.computeIfAbsent(r.getSteamId64(), k -> new HashMap<>())
                    .putAll(r.getMetrics());
        }

        for (Player player : participants) {
            Map<String, Double> m = porJogador.get(player.getSteamId64());
            if (m == null || m.isEmpty()) continue;

            Double tradeKills = m.get("tradeKills");
            Double tradesPorRound = (tradeKills != null && rounds > 0)
                    ? round2(tradeKills / rounds) : null;

            Team lado = descobrirLado(match, player);

            PlayerMatchStats stats = PlayerMatchStats.builder()
                    .playerSide(lado)
                    .won(venceu(match, lado))
                    .match(match)
                    .player(player)
                    .steamId64(player.getSteamId64())
                    .rankTier(match.getRankTier())
                    .csRating(match.getCsRating())
                    .roundsPlayed(rounds)
                    .adr(m.get("adr"))
                    .kdRatio(m.get("kdRatio"))
                    .headshotPercentage(m.get("headshotPercentage"))
                    .killsPerRound(m.get("killsPerRound"))
                    .deathsPerRound(m.get("deathsPerRound"))
                    .tradeKillsPerRound(tradesPorRound)
                    .openingDuelWinRate(m.get("openingDuelWinRate"))
                    .flashEfficiency(m.get("flashEfficiency"))
                    .utilityDamagePerRound(m.get("utilityDamagePerRound"))
                    .crosshairPlacementScore(m.get("crosshairPlacementScore"))
                    .kills(toInt(m.get("totalKills")))
                    .deaths(toInt(m.get("totalDeaths")))
                    .totalDamage(toInt(m.get("totalDamage")))
                    .build();

            playerMatchStatsRepository.save(stats);
        }

        log.debug("Desempenhos registrados para {} jogadores (faixa {}).",
                participants.size(), match.getRankTier());
    }

    /**
     * De que lado o jogador terminou a partida.
     *
     * <p>Só os eventos revelam isso — nem {@code Match} nem {@code Player}
     * guardam o time. Como o lado troca na metade da partida, vale o do
     * <b>último</b> evento em que o jogador aparece: é o lado com que ele
     * terminou, e é esse que o placar final reflete.</p>
     *
     * <p>Devolve {@code null} quando nenhum evento identifica o jogador —
     * alguém que entrou e saiu sem disparar, matar ou morrer.</p>
     */
    private Team descobrirLado(Match match, Player player) {
        Team lado = null;
        for (Round round : match.getRounds()) {
            for (MatchEvent e : round.getEvents()) {
                if (e.getActor() != null && player.getId().equals(e.getActor().getId())
                        && e.getActorSide() != null) {
                    lado = e.getActorSide();
                } else if (e.getVictim() != null && player.getId().equals(e.getVictim().getId())
                        && e.getVictimSide() != null) {
                    lado = e.getVictimSide();
                }
            }
        }
        return lado;
    }

    /**
     * Se o lado informado venceu a partida.
     *
     * <p>{@code null} quando o lado é desconhecido, o placar não foi
     * registrado ou a partida empatou — três situações em que afirmar vitória
     * ou derrota seria invenção.</p>
     */
    private Boolean venceu(Match match, Team lado) {
        Integer ct = match.getScoreCT();
        Integer tr = match.getScoreTR();
        if (lado == null || ct == null || tr == null || ct.equals(tr)) {
            return null;
        }
        return lado == Team.CT ? ct > tr : tr > ct;
    }

    private static Integer toInt(Double d) {
        return d == null ? null : (int) Math.round(d);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONVERSÃO: DTOs do Parser → Entidades JPA
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Converte o DTO raiz do parser em uma entidade Match com toda a árvore
     * de Rounds e MatchEvents.
     */
    private Match convertToMatch(String demoFileName, String demoFileHash,
                                  ParsedDemoDTO dto,
                                  Instant playedAtOverride,
                                  Map<String, Player> playerCache) {
        Match match = Match.builder()
                .demoFileName(demoFileName)
                .demoFileHash(demoFileHash)
                .mapName(dto.getMapName())
                .serverName(dto.getServerName())
                .durationSeconds(dto.getDurationSeconds())
                .scoreCT(dto.getScoreCT())
                .scoreTR(dto.getScoreTR())
                .totalRounds(dto.getTotalRounds())
                .tickRate(dto.getTickRate())
                .playedAt(playedAtOverride != null ? playedAtOverride : dto.getPlayedAt())
                .status(MatchStatus.PENDING)
                .build();

        if (dto.getRounds() != null) {
            for (ParsedRoundDTO roundDto : dto.getRounds()) {
                Round round = convertToRound(roundDto, playerCache);
                match.addRound(round);
            }
        }

        return match;
    }

    /**
     * Converte um DTO de round em uma entidade Round com seus MatchEvents.
     */
    private Round convertToRound(ParsedRoundDTO dto, Map<String, Player> playerCache) {
        Round round = Round.builder()
                .roundNumber(dto.getRoundNumber())
                .winnerSide(dto.getWinnerSide())
                .endReason(dto.getEndReason())
                .startTick(dto.getStartTick())
                .endTick(dto.getEndTick())
                .durationSeconds(dto.getDurationSeconds())
                .bombPlanted(Boolean.TRUE.equals(dto.getBombPlanted()))
                .bombDefused(Boolean.TRUE.equals(dto.getBombDefused()))
                .ctScoreAfter(dto.getCtScoreAfter())
                .trScoreAfter(dto.getTrScoreAfter())
                .build();

        if (dto.getEvents() != null) {
            for (ParsedEventDTO eventDto : dto.getEvents()) {
                MatchEvent event = convertToEvent(eventDto, playerCache);
                round.addEvent(event);
            }
        }

        return round;
    }

    /**
     * Converte um DTO de evento em uma entidade MatchEvent.
     *
     * <p>Resolve as referências de jogadores por SteamID64, criando novos registros
     * de {@link Player} se ainda não existirem no banco de dados (upsert por SteamID64).</p>
     */
    private MatchEvent convertToEvent(ParsedEventDTO dto, Map<String, Player> playerCache) {
        return MatchEvent.builder()
                .eventType(dto.getEventType())
                .tick(dto.getTick())
                .actor(resolvePlayer(dto.getActorSteamId(), dto.getActorName(), playerCache))
                .actorSide(dto.getActorSide())
                .victim(resolvePlayer(dto.getVictimSteamId(), dto.getVictimName(), playerCache))
                .victimSide(dto.getVictimSide())
                .assister(resolvePlayer(dto.getAssisterSteamId(), dto.getAssisterName(), playerCache))
                .weapon(dto.getWeapon())
                .isHeadshot(dto.getIsHeadshot())
                .damageAmount(dto.getDamageAmount())
                .damageArmor(dto.getDamageArmor())
                .flashDurationSeconds(dto.getFlashDurationSeconds())
                .isEnemyFlash(dto.getIsEnemyFlash())
                .actorPositionX(dto.getActorPositionX())
                .actorPositionY(dto.getActorPositionY())
                .actorPositionZ(dto.getActorPositionZ())
                .victimPositionX(dto.getVictimPositionX())
                .victimPositionY(dto.getVictimPositionY())
                .victimPositionZ(dto.getVictimPositionZ())
                .viewAngleX(dto.getViewAngleX())
                .viewAngleY(dto.getViewAngleY())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  RESOLUÇÃO DE JOGADORES (Upsert por SteamID64)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Resolve um jogador pelo SteamID64, fazendo upsert se necessário.
     *
     * <p>Lógica:</p>
     * <ol>
     *   <li>Se SteamID é nulo/vazio → retorna null (evento sem participante)</li>
     *   <li>Se está no cache local → retorna do cache</li>
     *   <li>Se existe no banco → atualiza o displayName e retorna</li>
     *   <li>Se não existe → cria novo Player e retorna</li>
     * </ol>
     *
     * @param playerCache cache com escopo de UMA chamada de processDemo — evita
     *                    centenas de consultas repetidas para os mesmos 10 jogadores
     *                    sem carregar entidades desanexadas entre transações
     */
    private Player resolvePlayer(String steamId64, String displayName,
                                  Map<String, Player> playerCache) {
        if (steamId64 == null || steamId64.isBlank()) {
            return null;
        }

        return playerCache.computeIfAbsent(steamId64, sid -> {
            Optional<Player> existing = playerRepository.findBySteamId64(sid);

            if (existing.isPresent()) {
                Player player = existing.get();
                // Atualiza o displayName caso tenha mudado na Steam
                if (displayName != null && !displayName.equals(player.getDisplayName())) {
                    player.setDisplayName(displayName);
                    return playerRepository.save(player);
                }
                return player;
            }

            // Novo jogador — cria registro
            Player newPlayer = Player.builder()
                    .steamId64(sid)
                    .displayName(displayName != null ? displayName : "Unknown")
                    .build();
            return playerRepository.save(newPlayer);
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EXTRAÇÃO DE PARTICIPANTES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Extrai o set único de jogadores que participaram da partida,
     * baseando-se nos atores e vítimas dos eventos.
     */
    private Set<Player> extractParticipants(Match match) {
        Set<Player> participants = new LinkedHashSet<>();

        for (Round round : match.getRounds()) {
            for (MatchEvent event : round.getEvents()) {
                if (event.getActor() != null) {
                    participants.add(event.getActor());
                }
                if (event.getVictim() != null) {
                    participants.add(event.getVictim());
                }
                if (event.getAssister() != null) {
                    participants.add(event.getAssister());
                }
            }
        }

        return participants;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ORQUESTRAÇÃO DAS STRATEGIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Aplica todas as strategies registradas para cada jogador participante.
     *
     * <p>Para N strategies e M jogadores, gera N × M resultados de métricas.
     * Cada strategy é independente e pode ser adicionada/removida sem
     * impactar as demais (Open/Closed Principle).</p>
     *
     * @param match        a partida com dados completos
     * @param participants set de jogadores participantes
     * @return lista de resultados de métricas por jogador por category
     */
    private List<PlayerStatResult> applyStrategies(Match match, Set<Player> participants) {
        List<PlayerStatResult> results = new ArrayList<>();

        for (Player player : participants) {
            for (StatCalculationStrategy strategy : strategies) {
                log.debug("Aplicando strategy '{}' para jogador '{}'",
                        strategy.getCategory(), player.getDisplayName());

                try {
                    PlayerStatResult result = strategy.calculate(match, player);
                    results.add(result);
                } catch (Exception e) {
                    log.error("Erro ao aplicar strategy '{}' para jogador '{}': {}",
                            strategy.getCategory(), player.getDisplayName(), e.getMessage(), e);
                    // Não interrompe o processamento — outras strategies continuam
                }
            }
        }

        return results;
    }
}
