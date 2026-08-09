package com.countatic.core.repository;

import com.countatic.core.entity.Round;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositório para acesso a dados de rounds.
 */
@Repository
public interface RoundRepository extends JpaRepository<Round, Long> {

    /**
     * Busca todos os rounds de uma partida específica, ordenados pelo número do round.
     *
     * @param matchId o ID da partida
     * @return lista de rounds ordenados
     */
    List<Round> findByMatchIdOrderByRoundNumberAsc(Long matchId);

    /**
     * Busca um round específico de uma partida pelo seu número sequencial.
     *
     * @param matchId     o ID da partida
     * @param roundNumber o número do round (1-indexed)
     * @return o round, se encontrado
     */
    Optional<Round> findByMatchIdAndRoundNumber(Long matchId, Integer roundNumber);

    /**
     * Conta quantos rounds existem em uma partida.
     *
     * @param matchId o ID da partida
     * @return total de rounds
     */
    long countByMatchId(Long matchId);

    /**
     * Carrega os rounds de uma partida com todos os eventos e os jogadores
     * envolvidos já materializados.
     *
     * <p>Uma partida tem milhares de eventos, cada um com até três referências
     * a {@code Player} (actor, victim, assister), todas LAZY. Sem estes fetch
     * joins, montar a página de detalhes dispararia dezenas de milhares de
     * consultas — o caso clássico de N+1.</p>
     *
     * <p>Roda como consulta separada de {@code findByIdWithRounds} porque o
     * Hibernate não aceita join fetch de duas coleções aninhadas
     * ({@code MultipleBagFetchException}). Como ambas rodam na mesma transação,
     * as entidades convergem no mesmo contexto de persistência.</p>
     */
    @Query("""
            SELECT DISTINCT r FROM Round r
            LEFT JOIN FETCH r.events e
            LEFT JOIN FETCH e.actor
            LEFT JOIN FETCH e.victim
            LEFT JOIN FETCH e.assister
            WHERE r.match.id = :matchId
            ORDER BY r.roundNumber ASC
            """)
    List<Round> findWithEventsByMatchId(@Param("matchId") Long matchId);
}
