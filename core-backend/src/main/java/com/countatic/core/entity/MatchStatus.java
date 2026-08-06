package com.countatic.core.entity;

/**
 * Ciclo de vida do processamento de uma demo.
 *
 * <ul>
 *   <li>{@code PENDING} — Arquivo recebido, aguardando envio ao Demo Parser (Go).</li>
 *   <li>{@code PROCESSING} — O Demo Parser está processando o arquivo .dem.</li>
 *   <li>{@code COMPLETED} — Parsing finalizado com sucesso, dados persistidos.</li>
 *   <li>{@code FAILED} — Erro durante o parsing (arquivo corrompido, formato inválido, etc.).</li>
 * </ul>
 */
public enum MatchStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
