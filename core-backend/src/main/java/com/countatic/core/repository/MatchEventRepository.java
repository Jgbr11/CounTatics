package com.countatic.core.repository;

import com.countatic.core.entity.EventType;
import com.countatic.core.entity.MatchEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositório para acesso a dados de eventos de partida.
 *
 * <p>Este repositório é o mais consultado para cálculos de métricas.
 * As queries customizadas permitem buscar eventos filtrados por jogador,
 * tipo e partida — exatamente o que o Strategy Pattern das métricas
 * precisará para os cálculos.</p>
 */
@Repository
public interface MatchEventRepository extends JpaRepository<MatchEvent, Long> {

    /**
     * Busca todos os eventos de um round específico, ordenados cronologicamente.
     *
     * @param roundId o ID do round
     * @return lista de eventos no round
     */
    List<MatchEvent> findByRoundIdOrderByTickAsc(Long roundId);

    /**
     * Busca todos os eventos de um tipo específico em um round.
     *
     * @param roundId   o ID do round
     * @param eventType o tipo de evento desejado
     * @return lista de eventos filtrados
     */
    List<MatchEvent> findByRoundIdAndEventType(Long roundId, EventType eventType);

    /**
     * Busca todos os eventos em que um jogador foi o ator (attacker/lançador),
     * filtrados por tipo de evento e dentro de uma partida específica.
     *
     * <p>Query essencial para cálculos como: "Quantas kills o jogador X fez
     * na partida Y?" ou "Quantas flashbangs o jogador X lançou?"</p>
     *
     * @param actorId  o ID do jogador
     * @param eventType o tipo de evento
     * @param matchId   o ID da partida
     * @return lista de eventos do jogador
     */
    @Query("""
            SELECT e FROM MatchEvent e
            WHERE e.actor.id = :actorId
              AND e.eventType = :eventType
              AND e.round.match.id = :matchId
            ORDER BY e.tick ASC
            """)
    List<MatchEvent> findByActorAndTypeInMatch(
            @Param("actorId") Long actorId,
            @Param("eventType") EventType eventType,
            @Param("matchId") Long matchId
    );

    /**
     * Busca todos os eventos em que um jogador foi a vítima,
     * filtrados por tipo e partida.
     *
     * @param victimId  o ID do jogador
     * @param eventType o tipo de evento
     * @param matchId   o ID da partida
     * @return lista de eventos sofridos pelo jogador
     */
    @Query("""
            SELECT e FROM MatchEvent e
            WHERE e.victim.id = :victimId
              AND e.eventType = :eventType
              AND e.round.match.id = :matchId
            ORDER BY e.tick ASC
            """)
    List<MatchEvent> findByVictimAndTypeInMatch(
            @Param("victimId") Long victimId,
            @Param("eventType") EventType eventType,
            @Param("matchId") Long matchId
    );

    /**
     * Busca todos os eventos de uma partida inteira, filtrados por tipo,
     * ordenados por round e tick.
     *
     * @param matchId   o ID da partida
     * @param eventType o tipo de evento
     * @return lista de eventos da partida
     */
    @Query("""
            SELECT e FROM MatchEvent e
            WHERE e.round.match.id = :matchId
              AND e.eventType = :eventType
            ORDER BY e.round.roundNumber ASC, e.tick ASC
            """)
    List<MatchEvent> findByMatchIdAndEventType(
            @Param("matchId") Long matchId,
            @Param("eventType") EventType eventType
    );

    /**
     * Conta o total de eventos de um tipo para um ator em uma partida.
     * Útil para métricas agregadas (total de kills, total de flashes, etc.).
     *
     * @param actorId   o ID do jogador
     * @param eventType o tipo de evento
     * @param matchId   o ID da partida
     * @return contagem de eventos
     */
    @Query("""
            SELECT COUNT(e) FROM MatchEvent e
            WHERE e.actor.id = :actorId
              AND e.eventType = :eventType
              AND e.round.match.id = :matchId
            """)
    long countByActorAndTypeInMatch(
            @Param("actorId") Long actorId,
            @Param("eventType") EventType eventType,
            @Param("matchId") Long matchId
    );
}
