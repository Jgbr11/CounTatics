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
 *   <li>{@code MVP} — Jogador foi eleito MVP do round.</li>
 *   <li>{@code CLUTCH_WON} — Jogador venceu um clutch (1vN).</li>
 *   <li>{@code TRADE_KILL} — Eliminação de trade (retaliação rápida após death de aliado).</li>
 * </ul>
 *
 * <p><b>Nota sobre {@code DEATH} e {@code ASSIST}:</b> o parser não os emite.
 * A linha de {@code KILL} já carrega {@code victim} e {@code assister}, então
 * gerar eventos separados dobraria o volume de linhas sem acrescentar
 * informação. As estratégias derivam mortes e assistências a partir do KILL.</p>
 *
 * <p><b>Nota sobre {@code CLUTCH_WON} e {@code TRADE_KILL}:</b> são derivados
 * no Java a partir dos KILLs, não emitidos pelo parser — dependem de contexto
 * de round que só faz sentido avaliar depois que todos os eventos existem.</p>
 */
public enum EventType {
    KILL,
    DEATH,
    ASSIST,
    MVP,
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
