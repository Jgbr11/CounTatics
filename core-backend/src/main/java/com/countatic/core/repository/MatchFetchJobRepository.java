package com.countatic.core.repository;

import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Acesso aos jobs de processamento de partida.
 */
public interface MatchFetchJobRepository extends JpaRepository<MatchFetchJob, Long> {

    /**
     * Localiza um job pela chave de deduplicação.
     * Usado por {@code enqueueIfAbsent} antes de tentar o insert.
     */
    Optional<MatchFetchJob> findBySteamId64AndShareCode(String steamId64, String shareCode);

    /**
     * Fila do worker: jobs prontos para uma nova tentativa.
     *
     * <p>Ordena por {@code nextAttemptAt} para que os mais atrasados saiam
     * primeiro, evitando que um job com backoff longo fique preterido para
     * sempre por jobs novos.</p>
     *
     * <p>O {@code LEFT JOIN FETCH j.match} é obrigatório: o worker precisa dos
     * dados da partida para montar a mensagem de notificação, e a associação é
     * LAZY. Sem o fetch, o proxy chega detached ao worker (a transação de
     * leitura já fechou) e qualquer acesso estoura
     * {@code could not initialize proxy - no Session}.</p>
     *
     * @param statuses estados elegíveis (os terminais ficam de fora)
     * @param now      instante atual
     * @param maxAttempts teto de tentativas antes de abandonar
     */
    @Query("""
            SELECT j FROM MatchFetchJob j
            LEFT JOIN FETCH j.match
            WHERE j.status IN :statuses
              AND j.attempts < :maxAttempts
              AND (j.nextAttemptAt IS NULL OR j.nextAttemptAt <= :now)
            ORDER BY j.nextAttemptAt ASC
            """)
    List<MatchFetchJob> findRunnable(@Param("statuses") List<MatchFetchStatus> statuses,
                                     @Param("now") Instant now,
                                     @Param("maxAttempts") int maxAttempts,
                                     org.springframework.data.domain.Pageable pageable);

    /**
     * Jobs que estouraram o teto de tentativas e precisam ser encerrados.
     */
    @Query("""
            SELECT j FROM MatchFetchJob j
            WHERE j.status IN :statuses
              AND j.attempts >= :maxAttempts
            """)
    List<MatchFetchJob> findExhausted(@Param("statuses") List<MatchFetchStatus> statuses,
                                      @Param("maxAttempts") int maxAttempts);

    /** Histórico de um jogador, mais recentes primeiro. */
    List<MatchFetchJob> findBySteamId64OrderByCreatedAtDesc(String steamId64);

    /** Existe algum job não-terminal para este share code? */
    boolean existsBySteamId64AndShareCodeAndStatusNotIn(String steamId64,
                                                        String shareCode,
                                                        List<MatchFetchStatus> statuses);
}
