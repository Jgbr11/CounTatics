package com.countatic.core.entity;

/**
 * Motivos possíveis para o fim de um round no CS2.
 *
 * <ul>
 *   <li>{@code CT_WIN_ELIMINATION} — CTs eliminaram todos os TRs.</li>
 *   <li>{@code TR_WIN_ELIMINATION} — TRs eliminaram todos os CTs.</li>
 *   <li>{@code BOMB_EXPLODED} — A bomba (C4) explodiu, vitória TR.</li>
 *   <li>{@code BOMB_DEFUSED} — A bomba foi desarmada, vitória CT.</li>
 *   <li>{@code CT_WIN_TIME} — Tempo do round expirou sem plant, vitória CT.</li>
 *   <li>{@code TARGET_SAVED} — Hostages foram resgatados (modo Hostage).</li>
 * </ul>
 */
public enum RoundEndReason {
    CT_WIN_ELIMINATION,
    TR_WIN_ELIMINATION,
    BOMB_EXPLODED,
    BOMB_DEFUSED,
    CT_WIN_TIME,
    TARGET_SAVED
}
