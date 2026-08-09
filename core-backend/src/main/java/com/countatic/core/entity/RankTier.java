package com.countatic.core.entity;

/**
 * Faixa de CS Rating (Premier), usada como grupo de comparação.
 *
 * <p><b>Por que estas faixas.</b> São exatamente as cores que o CS2 já exibe no
 * jogo. Inventar cortes próprios criaria uma escala que o jogador teria de
 * aprender; usando as do jogo, "você está acima da média do Azul" é uma frase
 * que ele entende sem explicação.</p>
 *
 * <p><b>Por que comparar por faixa e não globalmente.</b> Uma média que mistura
 * jogador de 3.000 com jogador de 30.000 não descreve ninguém: fica alta demais
 * para o iniciante e baixa demais para o experiente. Comparado ao seu próprio
 * nível, o número vira acionável.</p>
 */
public enum RankTier {

    CINZA("Cinza", 0, 4_999),
    AZUL_CLARO("Azul claro", 5_000, 9_999),
    AZUL("Azul", 10_000, 14_999),
    ROXO("Roxo", 15_000, 19_999),
    ROSA("Rosa", 20_000, 24_999),
    VERMELHO("Vermelho", 25_000, 29_999),
    OURO("Ouro", 30_000, Integer.MAX_VALUE);

    private final String displayName;
    private final int min;
    private final int max;

    RankTier(String displayName, int min, int max) {
        this.displayName = displayName;
        this.min = min;
        this.max = max;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    /** Rótulo legível com a faixa numérica (ex: "Azul (10.000–14.999)"). */
    public String getLabel() {
        if (this == OURO) {
            return String.format("%s (%,d+)", displayName, min).replace(',', '.');
        }
        return String.format("%s (%,d–%,d)", displayName, min, max).replace(',', '.');
    }

    /**
     * Classifica um CS Rating na faixa correspondente.
     *
     * @param rating CS Rating de Premier; {@code null} ou ≤ 0 significa
     *               "não calibrado", que não é o mesmo que rating baixo
     * @return a faixa, ou {@code null} se não houver rating utilizável
     */
    public static RankTier fromRating(Integer rating) {
        if (rating == null || rating <= 0) {
            return null;
        }
        for (RankTier tier : values()) {
            if (rating >= tier.min && rating <= tier.max) {
                return tier;
            }
        }
        return OURO;
    }
}
