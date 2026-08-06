package com.countatic.core.repository;

import com.countatic.core.entity.Match;
import com.countatic.core.entity.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositório para acesso a dados de partidas.
 */
@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {

    /**
     * Busca uma partida pelo hash SHA-256 do arquivo .dem.
     * Usado para verificar se uma demo já foi processada (deduplicação).
     *
     * @param demoFileHash hash SHA-256 do arquivo .dem
     * @return a partida, se encontrada
     */
    Optional<Match> findByDemoFileHash(String demoFileHash);

    /**
     * Verifica se uma demo com o hash informado já existe no sistema.
     *
     * @param demoFileHash hash SHA-256 do arquivo .dem
     * @return true se a demo já foi processada
     */
    boolean existsByDemoFileHash(String demoFileHash);

    /**
     * Busca todas as partidas com um determinado status de processamento.
     *
     * @param status o status desejado
     * @return lista de partidas com o status informado
     */
    List<Match> findByStatus(MatchStatus status);

    /**
     * Busca partidas jogadas em um mapa específico, ordenadas pela data mais recente.
     *
     * @param mapName nome do mapa (ex: "de_dust2")
     * @return lista de partidas naquele mapa
     */
    List<Match> findByMapNameOrderByPlayedAtDesc(String mapName);
}
