package com.countatic.core.dto.parser;

import com.countatic.core.entity.EventType;
import com.countatic.core.entity.Team;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representando um evento individual extraído pelo Demo Parser (Go).
 *
 * <p>Mapeia diretamente o JSON que o microsserviço Go devolve para cada
 * evento significativo encontrado no arquivo .dem. Campos irrelevantes
 * para o tipo de evento serão nulos.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedEventDTO {

    private EventType eventType;
    private Integer tick;

    // ─── Participantes ────────────────────────────────────────────────

    /** SteamID64 do jogador que executou a ação. */
    private String actorSteamId;

    /** Nome de exibição do ator no momento da partida. */
    private String actorName;

    /** Lado do ator (CT/TR). */
    private Team actorSide;

    /** SteamID64 da vítima (se aplicável). */
    private String victimSteamId;

    /** Nome de exibição da vítima. */
    private String victimName;

    /** Lado da vítima (CT/TR). */
    private Team victimSide;

    /** SteamID64 do jogador que assistiu (se aplicável). */
    private String assisterSteamId;

    /** Nome de exibição do assister. */
    private String assisterName;

    // ─── Detalhes do Evento ───────────────────────────────────────────

    private String weapon;
    private Boolean isHeadshot;
    private Integer damageAmount;
    private Integer damageArmor;
    private Double flashDurationSeconds;
    private Boolean isEnemyFlash;

    // ─── Dados Espaciais ──────────────────────────────────────────────

    private Double actorPositionX;
    private Double actorPositionY;
    private Double actorPositionZ;

    private Double victimPositionX;
    private Double victimPositionY;
    private Double victimPositionZ;

    private Double viewAngleX;
    private Double viewAngleY;
}
