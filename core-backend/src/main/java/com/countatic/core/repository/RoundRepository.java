package com.countatic.core.repository;

import com.countatic.core.entity.Round;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
