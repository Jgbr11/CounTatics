package com.countatic.core.controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Barra de navegação compartilhada pelas telas.
 *
 * <p><b>Montada em Java, não em JavaScript.</b> A navegação é a primeira coisa
 * que a pessoa procura ao chegar; renderizá-la junto do HTML faz ela existir
 * antes de qualquer script rodar — e continuar existindo se o script falhar.
 * Os componentes de dado podem esperar, a navegação não.</p>
 *
 * <p>Os destinos dependem de tokens que só o servidor conhece, então o
 * conjunto de links é montado por quem serve a página.</p>
 */
final class PageNav {

    private PageNav() {
    }

    /** Um item da barra. {@code ativo} marca a página atual. */
    record Item(String href, String rotulo, boolean ativo) {
    }

    /**
     * Acrescenta o item de sessão ao fim da barra.
     *
     * <p>Fica por último de propósito: entrar e sair não são navegação da
     * página, são estado da pessoa. Entre "Perfil" e "Partidas" pareceria mais
     * um destino.</p>
     *
     * <p>Sem sessão o convite é explícito — "Entrar com a Steam" diz o que vai
     * acontecer, enquanto "Entrar" deixa a pessoa se perguntando que conta o
     * site está pedindo.</p>
     */
    static List<Item> comSessao(boolean logado, List<Item> itens) {
        List<Item> todos = new ArrayList<>(itens);
        todos.add(logado
                ? item("/sair", "Sair", false)
                : item("/login", "Entrar com a Steam", false));
        return todos;
    }

    static Item item(String href, String rotulo, boolean ativo) {
        return new Item(href, rotulo, ativo);
    }

    /**
     * Monta a barra.
     *
     * <p>A marca à esquerda leva ao perfil quando ele é conhecido. Numa tela
     * de partida aberta por link direto da Steam pode não haver perfil — aí
     * ela vira texto, em vez de um link que não leva a lugar nenhum.</p>
     *
     * @param hrefPerfil destino da marca, ou {@code null} se desconhecido
     */
    static String render(String hrefPerfil, List<Item> itens) {
        StringBuilder sb = new StringBuilder();

        sb.append("<nav class=\"nav\"><div class=\"nav-in\">");

        String marca = "CounTatic";
        sb.append(hrefPerfil == null || hrefPerfil.isBlank()
                ? "<span class=\"nav-marca\">" + marca + "</span>"
                : "<a class=\"nav-marca\" href=\"" + escape(hrefPerfil) + "\">" + marca + "</a>");

        sb.append("<div class=\"nav-links\">");
        for (Item i : itens) {
            if (i == null || i.href() == null) continue;
            sb.append("<a href=\"").append(escape(i.href())).append('"');
            if (i.ativo()) {
                // aria-current dá o estado ao leitor de tela; o CSS se apoia
                // nele em vez de numa classe, para os dois não divergirem.
                sb.append(" aria-current=\"page\"");
            }
            sb.append('>').append(escape(i.rotulo())).append("</a>");
        }
        sb.append("</div></div></nav>");

        return sb.toString();
    }

    /** Conveniência: barra sem nenhum link, só a marca. */
    static String render(String hrefPerfil) {
        return render(hrefPerfil, new ArrayList<>());
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
