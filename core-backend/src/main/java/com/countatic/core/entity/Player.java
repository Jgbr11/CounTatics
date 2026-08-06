package com.countatic.core.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Representa um jogador de CS2 identificado pelo seu SteamID64.
 *
 * <p>Um Player pode participar de várias partidas. A relação M:N com Match
 * é resolvida através da entidade {@link MatchEvent} e dos rounds em que
 * o jogador aparece, evitando uma tabela de junção explícita neste momento.
 * Futuramente, caso necessário, podemos adicionar uma entidade intermediária
 * {@code MatchPlayer} para agregar estatísticas por partida.</p>
 */
@Entity
@Table(name = "players", indexes = {
        @Index(name = "idx_player_steam_id", columnList = "steamId64", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "matchEvents")
@EqualsAndHashCode(of = "id")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SteamID64 — identificador único e imutável do jogador na plataforma Steam.
     * Exemplo: "76561198012345678"
     */
    @NotBlank(message = "SteamID64 é obrigatório")
    @Size(min = 17, max = 17, message = "SteamID64 deve ter exatamente 17 dígitos")
    @Column(nullable = false, unique = true, length = 17)
    private String steamId64;

    /**
     * Nome de exibição do jogador na Steam no momento da última demo processada.
     * Pode mudar entre partidas — armazenamos o mais recente.
     */
    @NotBlank(message = "Nome do jogador é obrigatório")
    @Column(nullable = false, length = 64)
    private String displayName;

    /**
     * URL do avatar do jogador na Steam (opcional).
     */
    @Column(length = 512)
    private String avatarUrl;

    // ─── Autenticação Valve (CS2 Match Sharing API) ───────────────────

    /**
     * Game Authentication Code (código de autenticação gerado na página gamedata da Steam).
     * Exemplo: "AAAA-BBBB-CCCC" (18 caracteres)
     */
    @Column(length = 32)
    private String authCode;

    /**
     * Último código de compartilhamento de partida conhecido da Valve.
     * Exemplo: "CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx" (34 caracteres)
     */
    @Column(length = 64)
    private String latestShareCode;

    /**
     * Indica se a busca automática de partidas está ativa para este jogador.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean autoFetchEnabled = false;

    /**
     * Timestamp da última tentativa de consulta à API da Valve para este jogador.
     */
    private Instant lastPolledAt;

    /**
     * Timestamp da primeira vez que este jogador foi registrado no sistema.
     */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp da última atualização dos dados deste jogador.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Todos os eventos de partida em que este jogador foi o ator principal (attacker).
     */
    @OneToMany(mappedBy = "actor", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MatchEvent> matchEvents = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
