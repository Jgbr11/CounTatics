package com.countatic.core.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Representa um evento genérico ocorrido durante um round de CS2.
 *
 * <p>Esta é a entidade mais granular do modelo de dados. Cada tick relevante
 * da demo pode gerar um ou mais MatchEvents. O campo {@link #eventType}
 * determina a semântica dos demais campos:</p>
 *
 * <ul>
 *   <li><b>KILL/DEATH/TRADE_KILL:</b> actor (quem matou), victim (quem morreu),
 *       weapon, isHeadshot, damageAmount (dano letal)</li>
 *   <li><b>FLASH_BLINDED:</b> actor (quem lançou), victim (quem cegou),
 *       flashDurationSeconds</li>
 *   <li><b>DAMAGE:</b> actor, victim, weapon, damageAmount, damageArmor</li>
 *   <li><b>BOMB_PLANTED/DEFUSED/EXPLODED:</b> actor (quem plantou/desarmou),
 *       positionX/Y/Z</li>
 *   <li><b>WEAPON_FIRE:</b> actor, weapon, positionX/Y/Z, viewAngleX/Y
 *       (para crosshair placement analysis)</li>
 * </ul>
 *
 * <p>Nem todos os campos serão preenchidos para todos os tipos de evento.
 * Campos irrelevantes para um determinado {@link EventType} permanecerão nulos.</p>
 */
@Entity
@Table(name = "match_events", indexes = {
        @Index(name = "idx_event_round_id", columnList = "round_id"),
        @Index(name = "idx_event_type", columnList = "eventType"),
        @Index(name = "idx_event_actor_id", columnList = "actor_id"),
        @Index(name = "idx_event_victim_id", columnList = "victim_id"),
        @Index(name = "idx_event_tick", columnList = "tick")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"round", "actor", "victim", "assister"})
@EqualsAndHashCode(of = "id")
public class MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * O round em que este evento ocorreu.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_id", nullable = false)
    private Round round;

    /**
     * Tipo do evento — define quais campos são relevantes.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType eventType;

    /**
     * Tick do servidor em que o evento ocorreu.
     * Fundamental para ordenação cronológica e correlação de eventos
     * (ex: detectar trade kills — matar o inimigo em até X ticks após a morte de um aliado).
     */
    @Column(nullable = false)
    private Integer tick;

    // ─── Participantes ────────────────────────────────────────────────

    /**
     * Jogador que executou a ação (atirador, lançador de granada, plantador, etc.).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private Player actor;

    /**
     * Lado do ator no momento do evento (CT ou TR).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 2)
    private Team actorSide;

    /**
     * Jogador que sofreu a ação (vítima de kill, de dano, de flashbang, etc.).
     * Nulo para eventos sem vítima (ex: BOMB_PLANTED, WEAPON_FIRE).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "victim_id")
    private Player victim;

    /**
     * Lado da vítima no momento do evento.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 2)
    private Team victimSide;

    /**
     * Jogador que assistiu na eliminação (se aplicável).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assister_id")
    private Player assister;

    // ─── Detalhes do Evento ───────────────────────────────────────────

    /**
     * Arma utilizada no evento (ex: "ak47", "awp", "flashbang").
     * Usa a nomenclatura interna do CS2.
     */
    @Column(length = 64)
    private String weapon;

    /**
     * Se o tiro que matou foi headshot.
     */
    private Boolean isHeadshot;

    /**
     * Quantidade de dano causado (HP).
     */
    private Integer damageAmount;

    /**
     * Quantidade de dano absorvido pela armadura.
     */
    private Integer damageArmor;

    /**
     * Duração em segundos que a vítima ficou cegada (para FLASH_BLINDED).
     */
    private Double flashDurationSeconds;

    /**
     * Se a flash cegou um jogador do time inimigo (true) ou aliado (false).
     * Essencial para calcular eficiência de flashbang.
     */
    private Boolean isEnemyFlash;

    // ─── Dados Espaciais ──────────────────────────────────────────────

    /**
     * Posição X do ator no momento do evento (coordenadas do jogo).
     */
    private Double actorPositionX;

    /**
     * Posição Y do ator no momento do evento.
     */
    private Double actorPositionY;

    /**
     * Posição Z do ator no momento do evento (altura).
     */
    private Double actorPositionZ;

    /**
     * Posição X da vítima no momento do evento.
     */
    private Double victimPositionX;

    /**
     * Posição Y da vítima no momento do evento.
     */
    private Double victimPositionY;

    /**
     * Posição Z da vítima no momento do evento.
     */
    private Double victimPositionZ;

    /**
     * Ângulo de visão X (yaw) do ator no momento do disparo.
     * Usado para análise de crosshair placement.
     */
    private Double viewAngleX;

    /**
     * Ângulo de visão Y (pitch) do ator no momento do disparo.
     * Usado para análise de crosshair placement.
     */
    private Double viewAngleY;
}
