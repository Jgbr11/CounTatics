package com.countatic.core.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Representa um round individual dentro de uma partida de CS2.
 *
 * <p>Cada round tem um vencedor (CT ou TR), um motivo de vitória (eliminação,
 * tempo, bomba explodiu, bomba desarmada), e referencia todos os eventos
 * que ocorreram durante o round.</p>
 */
@Entity
@Table(name = "rounds", indexes = {
        @Index(name = "idx_round_match_id", columnList = "match_id"),
        @Index(name = "idx_round_match_number", columnList = "match_id, roundNumber", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"match", "events"})
@EqualsAndHashCode(of = "id")
public class Round {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A partida à qual este round pertence.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false)
    private Match match;

    /**
     * Número sequencial do round dentro da partida (1-indexed).
     */
    @Column(nullable = false)
    private Integer roundNumber;

    /**
     * Time vencedor do round: CT ou TR.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Team winnerSide;

    /**
     * Motivo da vitória do round.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoundEndReason endReason;

    /**
     * Tick do servidor em que o round iniciou (freeze time end).
     */
    @Column(nullable = false)
    private Integer startTick;

    /**
     * Tick do servidor em que o round terminou.
     */
    @Column(nullable = false)
    private Integer endTick;

    /**
     * Duração do round em segundos (calculada a partir dos ticks e tick rate).
     */
    @Column(nullable = false)
    private Double durationSeconds;

    /**
     * Se a bomba foi plantada neste round.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean bombPlanted = false;

    /**
     * Se a bomba foi desarmada neste round.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean bombDefused = false;

    /**
     * Placar acumulado do CT ao final deste round.
     */
    @Column(nullable = false)
    private Integer ctScoreAfter;

    /**
     * Placar acumulado do TR ao final deste round.
     */
    @Column(nullable = false)
    private Integer trScoreAfter;

    /**
     * Todos os eventos que ocorreram durante este round.
     * Ordenados pelo tick em que aconteceram.
     */
    @OneToMany(mappedBy = "round", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("tick ASC")
    @Builder.Default
    private List<MatchEvent> events = new ArrayList<>();

    // ─── Convenience Methods ──────────────────────────────────────────

    /**
     * Adiciona um evento a este round, mantendo a consistência bidirecional.
     */
    public void addEvent(MatchEvent event) {
        events.add(event);
        event.setRound(this);
    }

    /**
     * Remove um evento deste round, mantendo a consistência bidirecional.
     */
    public void removeEvent(MatchEvent event) {
        events.remove(event);
        event.setRound(null);
    }
}
