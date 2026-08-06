package com.countatic.core.service;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.countatic.core.dto.parser.ParsedEventDTO;
import com.countatic.core.dto.parser.ParsedRoundDTO;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.strategy.StatCalculationStrategy;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final PlayerRepository playerRepository;
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
                                 PlayerRepository playerRepository,
                                 List<StatCalculationStrategy> strategies) {
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;
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

        log.info("Iniciando processamento da demo: {} (hash: {})", demoFileName, demoFileHash);

        // ─── 1. Verificar duplicata ───────────────────────────────────────
        if (matchRepository.existsByDemoFileHash(demoFileHash)) {
            throw new IllegalStateException(
                    "Demo já processada anteriormente. Hash: " + demoFileHash);
        }

        // ─── 2. Converter DTOs em entidades ──────────────────────────────
        Match match = convertToMatch(demoFileName, demoFileHash, parsedDemo);
        log.debug("Match convertida: mapa={}, rounds={}", match.getMapName(), match.getTotalRounds());

        // ─── 3. Persistir no banco ────────────────────────────────────────
        match.setStatus(MatchStatus.PROCESSING);
        Match savedMatch = matchRepository.save(match);
        log.debug("Match salva com ID={}", savedMatch.getId());

        // ─── 4. Aplicar strategies e calcular métricas ────────────────────
        Set<Player> participants = extractParticipants(savedMatch);
        log.debug("Jogadores encontrados na partida: {}", participants.size());

        List<PlayerStatResult> allStats = applyStrategies(savedMatch, participants);

        // ─── 5. Marcar como concluída ─────────────────────────────────────
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

    // ═══════════════════════════════════════════════════════════════════
    //  CONVERSÃO: DTOs do Parser → Entidades JPA
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Converte o DTO raiz do parser em uma entidade Match com toda a árvore
     * de Rounds e MatchEvents.
     */
    private Match convertToMatch(String demoFileName, String demoFileHash,
                                  ParsedDemoDTO dto) {
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
                .playedAt(dto.getPlayedAt())
                .status(MatchStatus.PENDING)
                .build();

        if (dto.getRounds() != null) {
            for (ParsedRoundDTO roundDto : dto.getRounds()) {
                Round round = convertToRound(roundDto);
                match.addRound(round);
            }
        }

        return match;
    }

    /**
     * Converte um DTO de round em uma entidade Round com seus MatchEvents.
     */
    private Round convertToRound(ParsedRoundDTO dto) {
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
                MatchEvent event = convertToEvent(eventDto);
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
    private MatchEvent convertToEvent(ParsedEventDTO dto) {
        return MatchEvent.builder()
                .eventType(dto.getEventType())
                .tick(dto.getTick())
                .actor(resolvePlayer(dto.getActorSteamId(), dto.getActorName()))
                .actorSide(dto.getActorSide())
                .victim(resolvePlayer(dto.getVictimSteamId(), dto.getVictimName()))
                .victimSide(dto.getVictimSide())
                .assister(resolvePlayer(dto.getAssisterSteamId(), dto.getAssisterName()))
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
     * Cache local para evitar buscas repetidas ao banco durante o processamento
     * de uma mesma demo (que pode ter centenas de eventos com os mesmos 10 jogadores).
     */
    private final Map<String, Player> playerCache = new HashMap<>();

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
     */
    private Player resolvePlayer(String steamId64, String displayName) {
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
