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

              // Famílias de métrica. Poucos e genéricos de propósito: um ícone
              // por métrica daria trinta desenhos para manter, e o ganho de
              // leitura vem de agrupar visualmente, não de ilustrar cada uma.
              mira:    '<circle cx="12" cy="12" r="8"/><path d="M12 2v4m0 12v4M2 12h4m12 0h4"/>',
              morte:   '<path d="M9 21h6v-2H9zM12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/>',
              dano:    '<path d="M13 2 4 14h7l-1 8 9-12h-7l1-8z"/>',
              granada: '<path d="M12 22a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM12 8V5m-2-3h4"/>',
              fumaca:  '<path d="M5 17h13a3 3 0 0 0 0-6 5 5 0 0 0-9.6-1.6A3.5 3.5 0 0 0 5 17z"/>',
              duelo:   '<path d="m4 4 8 8m8-8-8 8m0 0-8 8m8-8 8 8"/>',
              relogio: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
            };

            /** Envelope do SVG. `stroke` em currentColor mantém a cor no controle do CSS. */
            const icon = (nome, tamanho) =>
              `<svg class="ico" width="${tamanho || 16}" height="${tamanho || 16}"
                    viewBox="0 0 24 24" fill="none" stroke="currentColor"
                    stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                    aria-hidden="true">${ICONS[nome] || ""}</svg>`;

            // ═══════════════════════════════════════════════════════════
            //  MetricCard
            // ═══════════════════════════════════════════════════════════

            /**
             * Card de uma métrica.
             *
             *   { label, valor, icone, status, principal, ajuda }
             *
             * `status` é 'acima' | 'abaixo' | 'neutro' e colore o NÚMERO, não a
             * caixa: o fundo é quase transparente justamente para o número ser
             * o único elemento com peso. `principal` dobra a largura e o
             * tamanho da fonte — é o que separa K/D de "disparos avaliados",
             * que antes tinham exatamente o mesmo peso visual.
             *
             * A marcação é de duas camadas porque o chanfro recorta a borda
             * junto com o fundo; sem a camada externa, a diagonal fica sem
             * linha.
             */
            function MetricCard({ label, valor, icone, status, principal, ajuda }) {
              const classes = ["chip", "cut"];
              if (status === "acima")  classes.push("is-acima");
              if (status === "abaixo") classes.push("is-abaixo");
              if (principal)           classes.push("is-principal");

              // O title nativo dá tooltip por mouse, teclado e leitor de tela
              // sem uma linha de JS de posicionamento.
              const attrAjuda = ajuda ? ` title="${esc(ajuda)}"` : "";

              // A seta acompanha a cor em vez de substituí-la. Verde e vermelho
              // é o par que o daltonismo mais comum não separa, então a forma é
              // o que garante a leitura — e continua legível em monitor mal
              // calibrado ou sob sol.
              const seta = status === "acima" ? "▲" : (status === "abaixo" ? "▼" : "");
              const sig = seta ? `<span class="sig" aria-hidden="true">${seta}</span>` : "";

              return `<div class="${classes.join(" ")}">
                  <div class="cut-in">
                    <div class="k"${attrAjuda}>${icone ? icon(icone, 13) : ""}<span>${esc(label)}</span></div>
                    <div class="v">${sig}${esc(valor)}</div>
                  </div>
                </div>`;
            }

            // ═══════════════════════════════════════════════════════════
            //  TrendChart
            // ═══════════════════════════════════════════════════════════

            /** IDs de gradiente precisam ser únicos: dois gráficos na mesma página colidiriam. */
            let _trendSeq = 0;

            /**
             * Gráfico de linha minimalista da evolução de uma métrica.
             *
             * Recebe a resposta de GET /api/players/{steamId}/trend:
             *   { label, maiorEhMelhor, media, pontos: [{playedAt, mapName, valor}] }
             *
             * Sem grade ao fundo de propósito — a única linha horizontal é a
             * média da própria série, que é a referência que dá sentido à
             * inclinação. Grade completa numa série de dez pontos acrescenta
             * dez linhas para informar o que dois rótulos já informam.
             *
             * SVG inline em vez de biblioteca: são quatro formas geométricas, e
             * qualquer lib de gráfico traria um bundler junto — o projeto não
             * tem nenhum, por decisão de arquitetura.
             */
            function TrendChart(serie) {
              const pts = (serie && serie.pontos) || [];
              const medidos = pts.filter(p => typeof p.valor === "number");

              if (medidos.length < 2) {
                return `<p class="hint">Ainda não há partidas suficientes para desenhar a evolução
                        de ${esc(serie && serie.label || "")}. São necessárias ao menos duas com a
                        métrica medida.</p>`;
              }

              const W = 640, H = 160, PAD_X = 8, PAD_Y = 16;
              const id = "tg" + (++_trendSeq);

              const valores = medidos.map(p => p.valor);
              let min = Math.min(...valores), max = Math.max(...valores);
              // Série constante viraria uma divisão por zero e uma linha colada
              // na borda; abrir uma folga a centraliza.
              if (min === max) { min -= 1; max += 1; }

              const x = i => PAD_X + (i * (W - 2 * PAD_X)) / (pts.length - 1 || 1);
              const y = v => PAD_Y + (H - 2 * PAD_Y) * (1 - (v - min) / (max - min));

              // Quebra a linha em segmentos: um ponto sem valor é métrica
              // AUSENTE, não zero. Ligar por cima fingiria uma medição.
              const segmentos = [];
              let atual = [];
              pts.forEach((p, i) => {
                if (typeof p.valor === "number") {
                  atual.push(`${x(i).toFixed(1)},${y(p.valor).toFixed(1)}`);
                } else if (atual.length) {
                  segmentos.push(atual); atual = [];
                }
              });
              if (atual.length) segmentos.push(atual);

              const linhas = segmentos
                .filter(s => s.length > 1)
                .map(s => `<polyline points="${s.join(" ")}" fill="none"
                            stroke="url(#${id}s)" stroke-width="2"
                            stroke-linecap="round" stroke-linejoin="round"/>`)
                .join("");

              // Área sob o maior segmento, só como reforço de leitura.
              const maior = segmentos.slice().sort((a, b) => b.length - a.length)[0] || [];
              const area = maior.length > 1
                ? `<polygon points="${maior[0].split(",")[0]},${H - PAD_Y} ${maior.join(" ")} ${maior[maior.length - 1].split(",")[0]},${H - PAD_Y}"
                      fill="url(#${id}a)" stroke="none"/>`
                : "";

              const bolinhas = pts.map((p, i) => {
                if (typeof p.valor !== "number") return "";
                const data = p.playedAt ? new Date(p.playedAt).toLocaleDateString("pt-BR") : "";
                return `<circle cx="${x(i).toFixed(1)}" cy="${y(p.valor).toFixed(1)}" r="3.5"
                          fill="var(--bg)" stroke="var(--neon)" stroke-width="2">
                          <title>${esc(p.mapName || "")} · ${esc(data)} · ${esc(p.valor)}</title>
                        </circle>`;
              }).join("");

              const media = typeof serie.media === "number" && serie.media >= min && serie.media <= max
                ? `<line x1="${PAD_X}" x2="${W - PAD_X}" y1="${y(serie.media).toFixed(1)}"
                         y2="${y(serie.media).toFixed(1)}" stroke="var(--line)"
                         stroke-width="1" stroke-dasharray="4 4"/>`
                : "";

              // Primeiro vs. último ponto medido: é a leitura que o jogador quer
              // ("estou melhorando?"), e ela respeita a direção da métrica.
              const ini = valores[0], fim = valores[valores.length - 1];
              const delta = fim - ini;
              const melhorou = serie.maiorEhMelhor ? delta > 0 : delta < 0;
              const neutro = Math.abs(delta) < 1e-9;
              const cls = neutro ? "dim" : (melhorou ? "up" : "down");
              const seta = neutro ? "—" : (delta > 0 ? "▲" : "▼");

              return `
                <div class="trend">
                  <div class="trend-head">
                    <span class="trend-label">${esc(serie.label)}</span>
                    <span class="${cls}"><span class="sig" aria-hidden="true">${seta}</span>${
                      esc((delta >= 0 ? "+" : "") + delta.toFixed(2))}</span>
                    <span class="dim">nas últimas ${pts.length} partidas</span>
                  </div>
                  <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"
                       role="img" aria-label="Evolução de ${esc(serie.label)}">
                    <defs>
                      <linearGradient id="${id}s" x1="0" y1="0" x2="1" y2="0">
                        <stop offset="0%" stop-color="#5B8DEF"/>
                        <stop offset="100%" stop-color="var(--neon)"/>
                      </linearGradient>
                      <linearGradient id="${id}a" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stop-color="rgba(176,38,255,.28)"/>
                        <stop offset="100%" stop-color="rgba(176,38,255,0)"/>
                      </linearGradient>
                    </defs>
                    ${media}${area}${linhas}${bolinhas}
                  </svg>
                  <div class="trend-foot">
                    <span class="dim mono">${esc(min.toFixed(1))}</span>
                    <span class="dim">média ${esc(typeof serie.media === "number" ? serie.media.toFixed(1) : "—")}</span>
                    <span class="dim mono">${esc(max.toFixed(1))}</span>
                  </div>
                </div>`;
            }

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
