package com.countatic.core.dto.stats;

/**
 * Uma dica de melhoria, com a gravidade que ela carrega.
 *
 * <p><b>Por que a gravidade nasce aqui e não no consumidor.</b> Cada faixa de
 * {@code if/else} das Strategies <i>já é</i> a gravidade: "excelente" e "bom"
 * são elogio, "na média" é constatação, "baixo" é alerta. O que faltava era o
 * tipo de retorno carregar essa informação em vez de jogá-la fora e obrigar
 * quem exibe a redescobri-la.</p>
 *
 * <p>Inferir do texto seria frágil a ponto de ser irresponsável. A palavra
 * "média" aparece nos três sentidos ("acima da média", "na média", "abaixo da
 * média"), quatro insights não têm palavra avaliativa nenhuma — um deles diz
 * literalmente "Não é erro" —, e os limiares vivem dentro de métodos privados.
 * Qualquer reescrita de texto quebraria a classificação em silêncio: sem erro
 * de compilação e sem teste vermelho.</p>
 *
 * <p><b>O texto não carrega ícone nem emoji.</b> Quem decide o símbolo é a
 * camada de exibição, a partir da {@link Severidade}. Emoji embutido no texto
 * duplicaria o ícone na página e apareceria fora de lugar na mensagem da
 * Steam.</p>
 *
 * @param texto     a mensagem para o jogador, já formatada e sem símbolos
 * @param gravidade quão urgente é agir sobre ela
 */
public record Insight(String texto, Severidade gravidade) {

    /**
     * Quão urgente é a dica.
     *
     * <p>A ordem da enum é a ordem de exibição: o que exige ação vem primeiro.</p>
     */
    public enum Severidade {
        /** Algo a corrigir. É o acionável, então aparece no topo. */
        AVISO,
        /** Constatação neutra ou contexto — nem elogio, nem problema. */
        INFO,
        /** Está indo bem. Vale mostrar para o jogador saber o que manter. */
        SUCESSO
    }

    /** Atalho de leitura para os pontos em que a gravidade é evidente no lugar da chamada. */
    public static Insight aviso(String texto) {
        return new Insight(texto, Severidade.AVISO);
    }

    public static Insight info(String texto) {
        return new Insight(texto, Severidade.INFO);
    }

    public static Insight sucesso(String texto) {
        return new Insight(texto, Severidade.SUCESSO);
    }
}
