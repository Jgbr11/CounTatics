package com.countatic.core.entity;

/**
 * Ciclo de vida do processamento de uma partida.
 *
 * <p>Estados <b>terminais</b> (o worker nunca mais toca no job):
 * {@link #NOTIFIED}, {@link #ABANDONED}, {@link #DEMO_EXPIRED}.
 * Todos os demais são retentáveis.</p>
 *
 * <pre>
 *   AWAITING_SHARECODE ──► PENDING ──► GC_DONE ──► DEMO_DONE ──► NOTIFIED
 *          │                  │           │            │
 *          │                  ▼           ▼            ▼
 *          └──────────────► FAILED ◄──────┴────────────┘
 *                             │
 *                             ▼
 *                        ABANDONED / DEMO_EXPIRED
 * </pre>
 */
public enum MatchFetchStatus {

    /**
     * O GSI avisou que a partida acabou, mas a Valve ainda não publicou o
     * share code. O worker sonda periodicamente até ele aparecer.
     *
     * <p>Existe porque o Game State Integration do CS2 não fornece share code,
     * match id nem demo URL — só o fim da partida e as stats locais.</p>
     */
    AWAITING_SHARECODE,

    /** Share code conhecido; falta consultar o Game Coordinator. */
    PENDING,

    /** Stats básicas obtidas do GC. Se houver demoUrl, o próximo passo é baixá-la. */
    GC_DONE,

    /** Demo baixada, parseada e analisada; falta notificar. */
    DEMO_DONE,

    /** Jogador notificado. Terminal. */
    NOTIFIED,

    /** Falha transitória — será retentada quando {@code nextAttemptAt} vencer. */
    FAILED,

    /**
     * Desistimos: tentativas esgotadas, ou o GC não conhece mais a partida
     * (fora da janela de retenção). Terminal.
     */
    ABANDONED,

    /**
     * A demo saiu do CDN da Valve (replays duram ~2 semanas). Terminal e
     * <b>sem retry</b> — nenhuma tentativa traz o arquivo de volta.
     * Distinto de {@link #ABANDONED} para não mascarar falha real como expiração.
     */
    DEMO_EXPIRED
}
