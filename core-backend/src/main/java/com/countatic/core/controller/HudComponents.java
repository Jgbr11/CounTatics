package com.countatic.core.controller;

/**
 * Componentes de UI reutilizáveis das telas web, em JavaScript.
 *
 * <p><b>Divisão de trabalho com o {@link HudTheme}:</b> lá ficam os tokens e as
 * classes CSS; aqui ficam as funções que montam a marcação. A separação existe
 * porque a tela de estado (404/500) usa só o CSS, e carregar as fábricas de
 * componentes num lugar que não tem dado nenhum para renderizar seria peso
 * morto.</p>
 *
 * <p><b>Por que funções em JS puro e não um framework:</b> as telas são HTML
 * autocontido gerado em Java, sem build de frontend, sem CORS e num container
 * só. Introduzir React custaria bundler, um segundo processo e uma origem
 * separada para resolver um problema que estas funções resolvem — elas são
 * reutilizáveis entre telas exatamente como um componente seria.</p>
 *
 * <p>Todo componente devolve <b>string de HTML</b>, nunca elemento do DOM: quem
 * chama concatena e faz um único {@code innerHTML}, o que evita reflow por
 * item numa lista.</p>
 */
final class HudComponents {

    private HudComponents() {
    }

    /** Vai dentro do {@code <script>}, antes do código específico da página. */
    static final String JS = """
            // ═══════════════════════════════════════════════════════════
            //  Utilitários compartilhados
            // ═══════════════════════════════════════════════════════════

            /**
             * Escapa texto para interpolação em HTML.
             *
             * Todo dado que vem do servidor passa por aqui: nome de jogador é
             * escolhido pelo usuário e pode conter marcação.
             */
            const esc = s => {
              const d = document.createElement("div");
              d.textContent = s ?? "";
              return d.innerHTML;
            };

            // ═══════════════════════════════════════════════════════════
            //  Ícones
            //  SVG inline: sem requisição de rede, e herdam a cor do texto
            //  por currentColor, então um mesmo ícone serve a qualquer estado.
            // ═══════════════════════════════════════════════════════════

            const ICONS = {
              aviso:   '<path d="M12 2 1 21h22L12 2zm0 6v7m0 3v.5"/>',
              sucesso: '<path d="M20 6 9 17l-5-5"/>',
              info:    '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4m0-4v.5"/>',
            };

            /** Envelope do SVG. `stroke` em currentColor mantém a cor no controle do CSS. */
            const icon = (nome, tamanho) =>
              `<svg class="ico" width="${tamanho || 16}" height="${tamanho || 16}"
                    viewBox="0 0 24 24" fill="none" stroke="currentColor"
                    stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                    aria-hidden="true">${ICONS[nome] || ""}</svg>`;

            // ═══════════════════════════════════════════════════════════
            //  CoachPanel
            // ═══════════════════════════════════════════════════════════

            /** Ordem de exibição: o acionável primeiro. Espelha a enum Severidade do Java. */
            const ORDEM_GRAVIDADE = { AVISO: 0, INFO: 1, SUCESSO: 2 };

            const ROTULO_GRAVIDADE = { AVISO: "Corrigir", INFO: "Observar", SUCESSO: "Manter" };

            /**
             * Painel consolidado de feedback.
             *
             * Recebe a lista já achatada de todas as categorias:
             *   [{ categoria, gravidade, texto }]
             *
             * Antes as dicas ficavam espalhadas em listas soltas abaixo de cada
             * categoria de métrica. O problema não era o volume — eram no máximo
             * 11 — e sim a falta de hierarquia: um elogio e um alerta tinham o
             * mesmo peso visual, e o jogador precisava ler tudo para achar o que
             * fazer. Aqui a ordenação por gravidade resolve isso sem esconder
             * nada.
             */
            function CoachPanel(insights) {
              if (!insights || !insights.length) {
                return `<p class="hint">Sem dicas para esta partida.</p>`;
              }

              const ordenados = [...insights].sort((a, b) =>
                (ORDEM_GRAVIDADE[a.gravidade] ?? 9) - (ORDEM_GRAVIDADE[b.gravidade] ?? 9));

              const itens = ordenados.map(i => {
                const g = (i.gravidade || "INFO").toLowerCase();
                return `<li class="coach-item is-${esc(g)}">
                    ${icon(g, 18)}
                    <div class="coach-txt">
                      <p>${esc(i.texto)}</p>
                      <span class="coach-meta">${esc(i.categoria)} · ${esc(ROTULO_GRAVIDADE[i.gravidade] || "")}</span>
                    </div>
                  </li>`;
              }).join("");

              return `<ul class="coach">${itens}</ul>`;
            }

            /**
             * Achata `insights` do DTO — { categoria: { chave: {texto, gravidade} } } —
             * na lista que o CoachPanel consome.
             *
             * A chave interna é descartada de propósito: ela é o identificador
             * técnico do insight ("utilityDamage", "smokeUsage") e não diz nada
             * ao jogador. Quem dá contexto é a categoria.
             */
            function achatarInsights(porCategoria) {
              const saida = [];
              for (const [categoria, mapa] of Object.entries(porCategoria || {})) {
                for (const ins of Object.values(mapa || {})) {
                  if (!ins || !ins.texto) continue;
                  saida.push({ categoria, texto: ins.texto, gravidade: ins.gravidade });
                }
              }
              return saida;
            }
            """;
}
