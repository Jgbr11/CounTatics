package com.countatic.core.repository;

import com.countatic.core.entity.Match;
import com.countatic.core.entity.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Busca pela URL pública enviada no chat da Steam ({@code /m/{token}}). */
    Optional<Match> findByPublicToken(String publicToken);

    /**
     * Carrega a partida com os rounds já materializados.
     *
     * <p>Com {@code open-in-view: false}, tocar numa associação LAZY fora da
     * transação estoura {@code LazyInitializationException}. Este fetch join
     * evita isso e mata o N+1 de rounds.</p>
     *
     * <p>Note que os <b>eventos</b> ficam de fora de propósito: fazer join
     * fetch de duas coleções aninhadas dispara
     * {@code MultipleBagFetchException}. Eles vêm numa segunda consulta
     * ({@code RoundRepository.findWithEventsByMatchId}) e o Hibernate junta
     * tudo no mesmo contexto de persistência.</p>
     */
    @Query("SELECT DISTINCT m FROM Match m LEFT JOIN FETCH m.rounds WHERE m.id = :id")
    Optional<Match> findByIdWithRounds(@Param("id") Long id);

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

    /** Partidas mais recentes primeiro, para listagem. */
    List<Match> findAllByOrderByPlayedAtDesc();
}
