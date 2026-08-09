package com.countatic.core.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;

/**
 * Unidade durável de trabalho para descobrir, analisar e notificar uma partida.
 *
 * <p><b>Por que esta tabela existe.</b> Antes, o {@code latestShareCode} do
 * jogador avançava e era commitado <i>antes</i> do processamento. Qualquer falha
 * adiante — Steam Bot reiniciando, GC sem responder em 15 s, CDN da Valve
 * instável, parser sem memória — apagava a partida em definitivo, sem retry,
 * sem marcador e sem nada visível ao usuário. Isso ocorreu de fato durante a
 * implementação: um {@code NoClassDefFoundError} no envio ao parser fez uma
 * partida real ser perdida, recuperável só rebobinando o share code via SQL.</p>
 *
 * <p>Um único registro cumpre quatro papéis:</p>
 * <ol>
 *   <li><b>Marcador de falha</b> — {@code status} + {@code lastError} tornam
 *       visível o que quebrou, em vez de sumir num {@code log.debug}.</li>
 *   <li><b>Fila de retry</b> — {@code nextAttemptAt} + {@code attempts}.</li>
 *   <li><b>Chave de deduplicação</b> — a constraint única
 *       {@code (steamId64, shareCode)} é o árbitro entre o GSI e o polling:
 *       quem inserir primeiro fica com a partida, o outro vira no-op.</li>
 *   <li><b>Read model</b> — alimenta {@code GET /api/players/{id}/jobs}, a
 *       superfície de diagnóstico de todo o pipeline.</li>
 * </ol>
 */
@Entity
@Table(
        name = "match_fetch_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_job_steamid_sharecode",
                columnNames = {"steamId64", "shareCode"}),
        indexes = {
                @Index(name = "idx_job_status_next_attempt", columnList = "status,nextAttemptAt"),
                @Index(name = "idx_job_steamid", columnList = "steamId64"),
                @Index(name = "idx_job_created", columnList = "createdAt")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(of = "id")
public class MatchFetchJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SteamID64 do jogador dono deste job. */
    @NotNull
    @Column(nullable = false, length = 17)
    private String steamId64;

    /**
     * Share code da partida.
     *
     * <p>Nulo enquanto o status for {@link MatchFetchStatus#AWAITING_SHARECODE}:
     * o GSI avisa que a partida acabou muito antes de a Valve publicar o código.</p>
     *
     * <p>Participa da constraint única. Em MySQL, valores NULL não colidem entre
     * si numa unique key — o que é exatamente o desejado, já que várias sondagens
     * pendentes do mesmo jogador podem coexistir legitimamente.</p>
     */
    @Column(length = 64)
    private String shareCode;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MatchFetchStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private FetchSource source;

    /** Partida persistida, preenchida quando a análise da demo conclui. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id")
    private Match match;

    /**
     * URL do replay no CDN da Valve, guardada assim que o GC responde.
     * Evita uma segunda ida ao GC quando o download é retentado.
     */
    @Column(length = 512)
    private String demoUrl;

    /** Timestamp unix do início da partida, informado pelo GC. */
    private Long matchTimeUnix;

    // ─── Controle de retry ────────────────────────────────────────────

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(length = 1024)
    private String lastError;

    /** Momento a partir do qual o worker pode pegar este job novamente. */
    private Instant nextAttemptAt;

    // ─── Controle de notificação (idempotência por classe de mensagem) ──

    /** Relatório preliminar do GSI (instantâneo, só as stats do jogador). */
    private Instant preliminarySentAt;

    /** Relatório básico do Game Coordinator (scoreboard completo). */
    private Instant notifiedBasicAt;

    /** Relatório profundo pós-análise da demo (métricas avançadas). */
    private Instant notifiedDeepAt;

    // ─── Dados vindos do GSI ──────────────────────────────────────────

    @Column(length = 64)
    private String gsiMapName;

    private Integer gsiScoreSelf;

    private Integer gsiScoreEnemy;

    /** JSON cru de {@code player.match_stats}, para o relatório preliminar. */
    @Column(length = 1024)
    private String gsiStatsJson;

    // ─── Auditoria ────────────────────────────────────────────────────

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Estados em que o worker não deve mais tocar no job. */
    public boolean isTerminal() {
        return status == MatchFetchStatus.NOTIFIED
                || status == MatchFetchStatus.ABANDONED
                || status == MatchFetchStatus.DEMO_EXPIRED;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.attempts == null) {
            this.attempts = 0;
        }
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
