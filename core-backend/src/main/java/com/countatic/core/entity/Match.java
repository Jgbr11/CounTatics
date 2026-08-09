package com.countatic.core.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Representa uma partida completa de CS2 extraída de um arquivo .dem.
 *
 * <p>Contém metadados da partida (mapa, duração, placar final) e referencia
 * a lista de rounds que compõem a partida. A demo original é identificada
 * pelo hash SHA-256 para evitar reprocessamento de arquivos duplicados.</p>
 */
@Entity
@Table(name = "matches", indexes = {
        @Index(name = "idx_match_demo_hash", columnList = "demoFileHash", unique = true),
        @Index(name = "idx_match_played_at", columnList = "playedAt"),
        @Index(name = "idx_match_public_token", columnList = "publicToken", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "rounds")
@EqualsAndHashCode(of = "id")
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Hash SHA-256 do arquivo .dem, usado para deduplicação.
     * Se uma demo já foi processada, não processamos novamente.
     */
    @NotBlank(message = "Hash do arquivo demo é obrigatório")
    @Column(nullable = false, unique = true, length = 64)
    private String demoFileHash;

    /**
     * Nome original do arquivo .dem enviado pelo usuário.
     */
    @NotBlank(message = "Nome do arquivo demo é obrigatório")
    @Column(nullable = false, length = 255)
    private String demoFileName;

    /**
     * Nome do mapa onde a partida foi jogada (ex: "de_dust2", "de_mirage").
     */
    @NotBlank(message = "Nome do mapa é obrigatório")
    @Column(nullable = false, length = 64)
    private String mapName;

    /**
     * Nome do servidor onde a partida ocorreu (se disponível na demo).
     */
    @Column(length = 128)
    private String serverName;

    /**
     * Duração total da partida em segundos.
     */
    @Column(nullable = false)
    private Integer durationSeconds;

    /**
     * Placar final do time CT ao encerrar a partida.
     */
    @Column(nullable = false)
    private Integer scoreCT;

    /**
     * Placar final do time TR ao encerrar a partida.
     */
    @Column(nullable = false)
    private Integer scoreTR;

    /**
     * Número total de rounds jogados na partida.
     */
    @Column(nullable = false)
    private Integer totalRounds;

    /**
     * Tick rate do servidor (64 ou 128, tipicamente).
     */
    @Column(nullable = false)
    private Integer tickRate;

    /**
     * Status do processamento da demo.
     * Controla o ciclo de vida: PENDING → PROCESSING → COMPLETED / FAILED
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchStatus status;

    /**
     * Mensagem de erro caso o processamento falhe (opcional).
     */
    @Column(length = 1024)
    private String errorMessage;

    /**
     * Token público e não-adivinhável usado na URL de detalhes da partida
     * ({@code /m/{publicToken}}), enviada ao jogador pelo chat da Steam.
     *
     * <p>É um UUID em vez do {@code id} sequencial porque a página não tem
     * autenticação: com id incremental qualquer pessoa varreria as partidas
     * de todos os jogadores só contando de 1 em diante.</p>
     */
    @Column(unique = true, length = 36)
    private String publicToken;

    /**
     * Data/hora em que a partida foi efetivamente jogada (extraída da demo).
     */
    @Column(nullable = false)
    private Instant playedAt;

    /**
     * Timestamp de quando o registro foi criado no sistema.
     */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp da última atualização.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Lista de rounds pertencentes a esta partida.
     * Ordenados pelo número do round.
     */
    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("roundNumber ASC")
    @Builder.Default
    private List<Round> rounds = new ArrayList<>();

    // ─── Convenience Methods ──────────────────────────────────────────

    /**
     * Adiciona um round a esta partida, mantendo a consistência bidirecional.
     */
    public void addRound(Round round) {
        rounds.add(round);
        round.setMatch(this);
    }

    /**
     * Remove um round desta partida, mantendo a consistência bidirecional.
     */
    public void removeRound(Round round) {
        rounds.remove(round);
        round.setMatch(null);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = MatchStatus.PENDING;
        }
        if (this.publicToken == null) {
            this.publicToken = java.util.UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
