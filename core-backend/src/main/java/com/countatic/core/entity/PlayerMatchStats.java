package com.countatic.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Desempenho de um jogador em uma partida, já agregado.
 *
 * <p><b>Por que esta tabela existe.</b> As métricas são calculadas a partir dos
 * eventos, mas calcular percentis exige varrer <i>todas</i> as partidas de uma
 * faixa. Fazer isso a partir de {@code match_events} significaria carregar
 * milhões de linhas a cada consulta. Aqui cada partida vira 10 linhas — uma por
 * jogador — e o percentil vira uma agregação SQL trivial.</p>
 *
 * <p><b>O detalhe que multiplica os dados:</b> cada partida analisada rende
 * <b>10</b> desempenhos, não 1. A base de comparação cresce dez vezes mais
 * rápido do que o número de partidas jogadas pelo usuário.</p>
 *
 * <p>As métricas guardadas são deliberadamente <b>normalizadas por round</b> ou
 * expressas como razão. Valores absolutos (total de kills, total de dano) não
 * são comparáveis entre partidas de 16 e de 30 rounds.</p>
 */
@Entity
@Table(
        name = "player_match_stats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pms_match_player",
                columnNames = {"match_id", "player_id"}),
        indexes = {
                // Índice que sustenta as consultas de percentil por faixa.
                @Index(name = "idx_pms_tier", columnList = "rankTier"),
                @Index(name = "idx_pms_steamid", columnList = "steamId64")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"match", "player"})
@EqualsAndHashCode(of = "id")
public class PlayerMatchStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false)
    private Match match;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** Duplicado do Player para permitir consultas sem join. */
    @Column(nullable = false, length = 17)
    private String steamId64;

    /**
     * Faixa da partida, derivada do CS Rating de quem a cadastrou.
     *
     * <p>Atribuída aos 10 jogadores porque o matchmaking pareia gente de nível
     * parecido — consultar o rating individual de cada um custaria 10 idas ao
     * Game Coordinator (~30 s) para uma precisão marginalmente melhor.</p>
     *
     * <p>{@code null} quando não há rating (conta não calibrada), e nesse caso
     * a linha não entra em nenhuma base de comparação.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RankTier rankTier;

    /** CS Rating bruto da partida, guardado para reclassificar se as faixas mudarem. */
    private Integer csRating;

    @Column(nullable = false)
    private Integer roundsPlayed;

    // ─── Métricas comparáveis ─────────────────────────────────────────

    /** Dano médio por round. */
    private Double adr;

    private Double kdRatio;
    private Double headshotPercentage;
    private Double killsPerRound;
    private Double deathsPerRound;

    /** Trocas por round — o absoluto não compara entre partidas de tamanhos diferentes. */
    private Double tradeKillsPerRound;

    private Double openingDuelWinRate;
    private Double flashEfficiency;
    private Double utilityDamagePerRound;
    private Double crosshairPlacementScore;

    // ─── Absolutos (exibição, não comparação) ─────────────────────────

    private Integer kills;
    private Integer deaths;
    private Integer assists;
    private Integer totalDamage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
