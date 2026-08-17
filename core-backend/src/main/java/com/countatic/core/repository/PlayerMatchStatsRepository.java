package com.countatic.core.repository;

import com.countatic.core.entity.PlayerMatchStats;
import com.countatic.core.entity.RankTier;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Últimos desempenhos do jogador, do mais recente para o mais antigo.
     *
     * <p><b>Ordena por {@code match.playedAt}, não por {@code createdAt}.</b>
     * O {@code createdAt} é o instante da <i>análise</i>, e
     * {@code MatchAnalysisService.recomputePlayerStats} apaga e regrava as
     * linhas — um recompute reescreveria o timestamp de uma partida antiga e
     * a jogaria para o topo da série. O eixo do gráfico tem que ser quando a
     * partida foi <i>jogada</i>.</p>
     *
     * <p>O {@code join fetch} traz a partida na mesma consulta: a relação é
     * {@code LAZY} e a aplicação roda com {@code open-in-view: false}, então
     * ler {@code playedAt} ou {@code mapName} depois estouraria fora da
     * transação. Como {@code match} é {@code @ManyToOne} (valor único), a
     * paginação continua acontecendo no SQL — o problema de paginar em
     * memória atinge apenas fetch de coleção.</p>
     */
    @Query("select s from PlayerMatchStats s join fetch s.match m "
            + "where s.steamId64 = :steamId order by m.playedAt desc")
    List<PlayerMatchStats> findRecentesComPartida(@Param("steamId") String steamId, Pageable pageable);

    /** Total de desempenhos registrados na faixa. */
    long countByRankTier(RankTier rankTier);

    void deleteByMatchId(Long matchId);
}
