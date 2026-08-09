package com.countatic.core.entity;

/**
 * Origem que descobriu a partida.
 *
 * <p>Serve para diagnóstico: se os jobs de {@link #GSI} pararem de aparecer,
 * o arquivo {@code gamestate_integration_countatic.cfg} provavelmente saiu do
 * lugar ou o CS2 foi reinstalado — e o sistema segue funcionando via
 * {@link #POLL} sem nenhum erro visível.</p>
 */
public enum FetchSource {

    /** Descoberta pelo scheduler periódico consultando a API da Valve. */
    POLL,

    /** Descoberta pelo Game State Integration do CS2, no fim da partida. */
    GSI,

    /** Disparo manual via endpoint REST (fetch-now ou upload de demo). */
    MANUAL
}
