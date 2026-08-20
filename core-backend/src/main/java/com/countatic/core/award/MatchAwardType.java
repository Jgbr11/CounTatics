package com.countatic.core.award;

/**
 * Títulos que um jogador pode receber ao fim de uma partida.
 *
 * <p>Um jogador recebe <b>no máximo um</b>: o de maior prioridade entre os que
 * ele satisfaz. Distribuir vários diluiria o destaque — se todo mundo ganha
 * três títulos, nenhum significa nada.</p>
 *
 * <p><b>A prioridade é o desempate, não a importância.</b> Número maior vence.
 * Ela existe porque as condições se sobrepõem de propósito: quem faz 2.5 de
 * K/D provavelmente também tem ADR alto, e o título mais específico deve
 * ganhar do mais genérico. Os cômicos ficam abaixo dos épicos para ninguém
 * ser chamado de "primeiro a cair" numa partida em que carregou o time.</p>
 */
public enum MatchAwardType {

    // ─── Épicos ───────────────────────────────────────────────────────
    // Reservados a desempenho excepcional. Ficam no topo da prioridade
    // porque, quando aparecem, são a informação mais relevante da linha.

    CIRURGIAO(Categoria.EPICO, 10, "Cirurgião",
            "Mais de 60% das kills na cabeça — precisão de outro patamar."),

    IMPARAVEL(Categoria.EPICO, 9, "Imparável",
            "K/D acima de 2.0. Dominou os duelos do começo ao fim."),

    REI_DO_CLUTCH(Categoria.EPICO, 9, "Rei do Clutch",
            "Venceu dois ou mais rounds sozinho contra o time adversário."),

    SNIPER(Categoria.EPICO, 8, "Sniper",
            "Venceu a maioria esmagadora dos duelos longos."),

    ABRE_ALAS(Categoria.EPICO, 8, "Abre-alas",
            "Ganhou a maior parte dos primeiros duelos e abriu os rounds."),

    MAQUINA_DE_DANO(Categoria.EPICO, 7, "Máquina de Dano",
            "ADR acima de 100. Machucou o time inimigo todo round."),

    // ─── Neutros ──────────────────────────────────────────────────────
    // Reconhecem função bem executada. É o que a maioria recebe.

    ARQUITETO(Categoria.NEUTRO, 5, "Arquiteto",
            "Utilitária que criou espaço: flashes eficientes e dano de granada."),

    SOMBRA(Categoria.NEUTRO, 4, "Sombra",
            "Vingou as mortes dos aliados e manteve o time inteiro."),

    MURALHA(Categoria.NEUTRO, 4, "Muralha",
            "Morreu pouco e segurou a posição até o fim dos rounds."),

    DUELISTA_DE_PERTO(Categoria.NEUTRO, 3, "Duelista",
            "Levou vantagem clara nos duelos de curta distância."),

    // ─── Cômicos ──────────────────────────────────────────────────────
    // Apontam um padrão real e ruim, com bom humor. Prioridade baixa de
    // propósito: só aparecem quando não houve nada melhor a dizer.

    LANTERNA_DO_TIME(Categoria.COMICO, 2, "Lanterna do Time",
            "Cegou mais aliado do que inimigo. Revise os line-ups."),

    PRIMEIRO_A_CAIR(Categoria.COMICO, 2, "Primeiro a Cair",
            "Metade das mortes nos primeiros 15 segundos do round."),

    TURISTA(Categoria.COMICO, 1, "Turista",
            "Passou a partida sem lançar utilitária nem abrir duelo."),

    LONGE_DEMAIS(Categoria.COMICO, 1, "Longe Demais",
            "Insistiu em duelos longos e perdeu quase todos.");

    /** Como o título deve ser lido — e, na interface, com que cor. */
    public enum Categoria {
        /** Desempenho excepcional. Dourado. */
        EPICO,
        /** Função bem executada. Cor da marca. */
        NEUTRO,
        /** Padrão ruim, dito com humor. Rosa. */
        COMICO
    }

    private final Categoria categoria;
    private final int prioridade;
    private final String nome;
    private final String descricao;

    MatchAwardType(Categoria categoria, int prioridade, String nome, String descricao) {
        this.categoria = categoria;
        this.prioridade = prioridade;
        this.nome = nome;
        this.descricao = descricao;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    /** Maior vence o desempate. */
    public int getPrioridade() {
        return prioridade;
    }

    public String getNome() {
        return nome;
    }

    public String getDescricao() {
        return descricao;
    }
}
