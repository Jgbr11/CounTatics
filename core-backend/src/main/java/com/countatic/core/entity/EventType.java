package com.countatic.core.entity;

/**
 * Tipos de eventos que podem ocorrer durante um round de CS2.
 *
 * <p>Esta enum define a taxonomia de eventos que o Demo Parser (Go) extrai
 * do arquivo .dem e envia ao Core Backend. O tipo do evento determina quais
 * campos da entidade {@link MatchEvent} são relevantes.</p>
 *
 * <ul>
 *   <li>{@code KILL} — Um jogador eliminou outro. Campos: actor, victim, weapon, isHeadshot.</li>
 *   <li>{@code DEATH} — Morte de um jogador (inverso de KILL, útil para análise da vítima).</li>
 *   <li>{@code ASSIST} — Assistência em uma eliminação.</li>
 *   <li>{@code FLASH_THROWN} — Flashbang arremessada.</li>
 *   <li>{@code FLASH_BLINDED} — Um jogador foi cegado por uma flashbang. Campos: actor (quem jogou), victim (quem cegou).</li>
 *   <li>{@code SMOKE_THROWN} — Smoke granada arremessada.</li>
 *   <li>{@code HE_THROWN} — HE granada arremessada.</li>
 *   <li>{@code MOLOTOV_THROWN} — Molotov/Incendiária arremessada.</li>
 *   <li>{@code BOMB_PLANTED} — C4 plantada.</li>
 *   <li>{@code BOMB_DEFUSED} — C4 desarmada.</li>
 *   <li>{@code BOMB_EXPLODED} — C4 explodiu.</li>
 *   <li>{@code DAMAGE} — Dano causado a outro jogador.</li>
 *   <li>{@code WEAPON_FIRE} — Disparo de arma (para cálculo de crosshair placement).</li>
 *   <li>{@code CLUTCH_WON} — Jogador venceu um clutch (1vN).</li>
 *   <li>{@code TRADE_KILL} — Eliminação de trade (retaliação rápida após death de aliado).</li>
 * </ul>
 */
public enum EventType {
    KILL,
    DEATH,
    ASSIST,
    FLASH_THROWN,
    FLASH_BLINDED,
    SMOKE_THROWN,
    HE_THROWN,
    MOLOTOV_THROWN,
    BOMB_PLANTED,
    BOMB_DEFUSED,
    BOMB_EXPLODED,
    DAMAGE,
    WEAPON_FIRE,
    CLUTCH_WON,
    TRADE_KILL
}
