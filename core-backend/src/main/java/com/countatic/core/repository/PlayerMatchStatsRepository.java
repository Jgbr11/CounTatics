package com.countatic.core.repository;

import com.countatic.core.entity.PlayerMatchStats;
import com.countatic.core.entity.RankTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Acesso aos desempenhos agregados — a base das comparações por faixa.
 *
 * <p>As consultas de percentil, que precisam variar a coluna da métrica, ficam
 * em {@code BaselineService} usando {@code EntityManager} com uma whitelist de
 * colunas. Não dá para expressá-las aqui: nem JPQL nem {@code @Query} permitem
 * parametrizar o nome de uma coluna.</p>
 */
@Repository
public interface PlayerMatchStatsRepository extends JpaRepository<PlayerMatchStats, Long> {

    Optional<PlayerMatchStats> findByMatchIdAndPlayerId(Long matchId, Long playerId);

    List<PlayerMatchStats> findByMatchId(Long matchId);

    List<PlayerMatchStats> findBySteamId64OrderByCreatedAtDesc(String steamId64);

    /** Total de desempenhos registrados na faixa. */
    long countByRankTier(RankTier rankTier);

    void deleteByMatchId(Long matchId);
}
