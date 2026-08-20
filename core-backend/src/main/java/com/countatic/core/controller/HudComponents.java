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

    private static final String COMPONENTES = """
            // ═══════════════════════════════════════════════════════════
            //  Movimento
            // ═══════════════════════════════════════════════════════════

            /**
             * Motor de animação da interface.
             *
             * São dois mecanismos, e cada um resolve um caso que o outro não
             * resolve bem:
             *
             *  - `revelar` usa IntersectionObserver e serve para o que já está
             *    na marcação quando a página abre (os painéis). Ele cobre de
             *    graça os dois comportamentos: o que está visível anima na
             *    abertura, o que está abaixo anima ao chegar na tela.
             *
             *  - `entrar` anima o que o JS insere depois (cards, linhas,
             *    destaques). Aqui o observer não serve: o elemento nasce já
             *    dentro da área visível e o gesto certo é animar na inserção.
             *
             * Tudo passa por MOVIMENTO_OK. Quem configurou "menos movimento" no
             * sistema recebe a página pronta, sem transição e sem contagem — e
             * a informação aparece igual, que é o ponto.
             */
            const MOVIMENTO_OK = !(window.matchMedia
              && window.matchMedia("(prefers-reduced-motion: reduce)").matches);

            const Motion = (() => {

              /* O escalonamento é contado por quadro: os elementos que entram
                 juntos se encadeiam, e os que entram depois recomeçam do zero.
                 Sem isso, rolar a página rápido acumularia atraso e os últimos
                 painéis apareceriam segundos atrasados. */
              let lote = 0, quadroDoLote = -1;

              /* O observer já entregou alguma coisa? A revelação é enfeite, mas
                 o conteúdo por trás dela não é: num ambiente onde o observer
                 não dispare, a página ficaria em branco com o JS funcionando
                 perfeitamente. A rede abaixo cobre esse caso — e some sozinha
                 assim que o observer dá sinal de vida, para não atropelar a
                 revelação por scroll de quem está com tudo funcionando. */
              let observerVivo = false;

              function atraso() {
                const agora = Math.floor(performance.now() / 100);
                if (agora !== quadroDoLote) { quadroDoLote = agora; lote = 0; }
                // Teto de seis: passar disso vira espera, não encadeamento.
                return Math.min(lote++, 6);
              }

              const obs = ("IntersectionObserver" in window) ? new IntersectionObserver(
                (entradas, self) => {
                  observerVivo = true;
                  entradas.forEach(e => {
                    if (!e.isIntersecting) return;
                    self.unobserve(e.target);
                    mostrar(e.target);
                  });
                },
                // Começa um pouco antes de entrar de fato: a animação termina
                // quando o bloco está de vez na tela, e não depois.
                { rootMargin: "0px 0px -8% 0px", threshold: 0.05 }
              ) : null;

              let rede = 0;

              function mostrar(el) {
                el.style.setProperty("--atraso", (atraso() * 55) + "ms");
                el.classList.add("is-visivel");
                contarDentro(el);
              }

              return {
                /** Painéis e blocos que já existem na marcação. */
                revelar(seletor, raiz) {
                  const alvos = (raiz || document).querySelectorAll(seletor);
                  alvos.forEach(el => {
                    if (!MOVIMENTO_OK || !obs) { contarDentro(el); return; }
                    el.classList.add("revelar");
                    obs.observe(el);
                  });

                  clearTimeout(rede);
                  rede = setTimeout(() => {
                    if (observerVivo) return;
                    document.querySelectorAll(".revelar:not(.is-visivel)")
                            .forEach(mostrar);
                  }, 1500);
                },

                /**
                 * Conteúdo recém-inserido pelo JS.
                 *
                 * Chamar depois do innerHTML: os elementos precisam existir.
                 */
                entrar(raiz, seletor) {
                  const alvos = raiz.querySelectorAll(seletor || ":scope > *");
                  alvos.forEach((el, i) => {
                    if (MOVIMENTO_OK) {
                      el.style.setProperty("--atraso", (Math.min(i, 10) * 40) + "ms");
                      el.classList.add("entrar");
                    }
                  });
                  contarDentro(raiz);
                },

                contarDentro,
                contar,
              };

              function contarDentro(raiz) {
                raiz.querySelectorAll("[data-contar]").forEach(contar);
                crescer(raiz);
              }

              /**
               * Barras que nascem em zero e crescem até o valor.
               *
               * A largura final vem no atributo, não no style: aplicá-la um
               * quadro depois é o que dá ao navegador um estado inicial de onde
               * transicionar. Definir os dois no mesmo quadro faria a barra
               * aparecer pronta.
               */
              function crescer(raiz) {
                const barras = raiz.querySelectorAll("[data-largura]");
                if (!barras.length) return;

                barras.forEach(b => {
                  const w = b.dataset.largura;
                  delete b.dataset.largura;
                  if (!MOVIMENTO_OK) { b.style.width = w; return; }
                  requestAnimationFrame(() => requestAnimationFrame(() => {
                    b.style.width = w;
                  }));
                });
              }

              /**
               * Faz o número subir até o valor final.
               *
               * Lê o próprio texto já renderizado em vez de receber o número:
               * assim o formato — casas decimais, sinal de porcentagem, "—"
               * para métrica ausente — continua sendo decidido em um lugar só,
               * por quem monta o card.
               *
               * Métrica ausente não conta de zero até nada: ela nem entra aqui.
               */
              function contar(el) {
                if (el.dataset.contado === "1") return;
                el.dataset.contado = "1";

                const bruto = el.textContent.trim();
                const m = /^(-?\\d+(?:\\.\\d+)?)(.*)$/.exec(bruto);
                if (!m || !MOVIMENTO_OK) return;

                const alvo = parseFloat(m[1]);
                const sufixo = m[2] || "";
                const casas = (m[1].split(".")[1] || "").length;

                // Números pequenos e inteiros (uma contagem de kills, por
                // exemplo) não ganham nada em contar: o efeito só aparece
                // quando há distância para percorrer.
                if (!isFinite(alvo) || Math.abs(alvo) < 3) return;

                const inicio = performance.now();
                const duracao = 700;

                function passo(agora) {
                  const t = Math.min((agora - inicio) / duracao, 1);
                  // Mesma curva do CSS: expo-out.
                  const e = 1 - Math.pow(2, -10 * t);
                  const v = alvo * (t === 1 ? 1 : e);
                  el.textContent = v.toFixed(casas) + sufixo;
                  if (t < 1) requestAnimationFrame(passo);
                  else el.textContent = bruto;
                }

                el.textContent = (0).toFixed(casas) + sufixo;
                requestAnimationFrame(passo);
              }
            })();

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
            //  Última partida vista
            //  A barra leva ao perfil e à lista, mas sair de uma partida
            //  deixava o jogador sem volta em um clique — ele teria de
            //  reencontrá-la na lista. Guardar a última vista resolve isso
            //  com precisão, e não por aproximação: "a mais recente" nem
            //  sempre é a que ele estava lendo.
            // ═══════════════════════════════════════════════════════════

            /**
             * sessionStorage, não localStorage: o atalho vale para a visita
             * atual. Voltar amanhã e ser levado a uma partida de ontem seria
             * confuso.
             *
             * Todo acesso é protegido — o armazenamento pode estar bloqueado
             * (janela privada, arquivo local), e o atalho é conveniência: nunca
             * pode derrubar a página.
             */
            const PARTIDA_VISTA = "countatic:ultimaPartida";

            function lembrarPartida(token, rotulo) {
              if (!token) return;
              try {
                sessionStorage.setItem(PARTIDA_VISTA, JSON.stringify({ token, rotulo }));
              } catch (e) { /* sem armazenamento, sem atalho */ }
            }

            /** Acrescenta o atalho à barra, se houver partida lembrada. */
            function oferecerVoltaAPartida() {
              let dados = null;
              try {
                dados = JSON.parse(sessionStorage.getItem(PARTIDA_VISTA) || "null");
              } catch (e) { return; }

              if (!dados || !dados.token) return;

              const links = document.querySelector(".nav-links");
              if (!links) return;

              const a = document.createElement("a");
              a.href = "/m/" + encodeURIComponent(dados.token);
              a.className = "nav-volta";
              a.textContent = "← " + (dados.rotulo || "Partida");
              a.title = "Voltar à última partida que você abriu";
              links.prepend(a);
            }

            // ═══════════════════════════════════════════════════════════
            //  Faixas de rank
            // ═══════════════════════════════════════════════════════════

            /**
             * Classe CSS da faixa: "AZUL_CLARO" -> " t-azul-claro".
             *
             * Devolve string vazia quando a faixa é desconhecida, e aí o
             * elemento cai no fallback da cor da marca. Todo lugar que mostra
             * faixa passa por aqui — sem isso, cada tela derivaria a classe do
             * seu jeito e uma delas ficaria para trás na primeira faixa nova.
             */
            const classeTier = tier =>
              tier ? " t-" + String(tier).toLowerCase().replace(/_/g, "-") : "";

            /** Nome legível da faixa a partir do enum: "AZUL_CLARO" -> "Azul claro". */
            const nomeTier = tier =>
              tier ? tier.charAt(0) + String(tier).slice(1).toLowerCase().replace(/_/g, " ") : "";

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
            function MetricCard({ label, valor, icone, status, principal, ajuda, spark, chave, recorde }) {
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

              // A chave identifica o card para quem acrescenta a sparkline
              // depois, quando a resposta do histórico chega.
              const attrChave = chave ? ` data-metrica="${esc(chave)}"` : "";

              // Recorde pessoal no mapa. Vai depois do número e não no lugar
              // dele: o valor continua sendo a informação, a chama é o adorno.
              const pr = recorde
                ? `<span class="pr" title="Seu melhor resultado neste mapa">🏆</span>`
                : "";
              if (recorde) classes.push("is-recorde");

              return `<div class="${classes.join(" ")}"${attrChave}>
                  <div class="cut-in">
                    <div class="k"${attrAjuda}>${icone ? icon(icone, 13) : ""}<span>${esc(label)}</span></div>
                    <div class="v">${sig}<span class="num" data-contar>${esc(valor)}</span>${pr}</div>
                    ${spark || ""}
                  </div>
                </div>`;
            }

            // ═══════════════════════════════════════════════════════════
            //  MatchBadge
            // ═══════════════════════════════════════════════════════════

            /**
             * Selo do título da partida.
             *
             * A cor vem da categoria, não do nome: dourado para épico, cor da
             * marca para neutro, rosa para cômico. Assim o jogador entende o
             * tom antes de ler — e "Lanterna do Time" em dourado passaria a
             * impressão errada por um instante.
             *
             * O texto completo fica no title: no placar há espaço para o nome,
             * não para a explicação.
             */
            function MatchBadge(award, tamanho) {
              if (!award || !award.nome) return "";

              const cat = String(award.categoria || "NEUTRO").toLowerCase();
              const cls = "badge-award is-" + esc(cat) + (tamanho === "grande" ? " is-grande" : "");

              return `<span class="${cls}" title="${esc(award.descricao || "")}">${esc(award.nome)}</span>`;
            }

            // ═══════════════════════════════════════════════════════════
            //  RadarChart
            // ═══════════════════════════════════════════════════════════

            /**
             * Referências de cada eixo do radar.
             *
             * As métricas que já são porcentagem entram direto. As que não são
             * precisam de um teto para virar escala — e o teto é uma escolha,
             * não um máximo do jogo: 120 de ADR ou 20 de dano de utilitária por
             * round são desempenhos muito bons, não o limite físico. Quem passar
             * disso satura em 100%, o que é aceitável num gráfico cuja pergunta
             * é "qual foi meu papel", não "quanto exatamente eu fiz".
             *
             * Escala ABSOLUTA de propósito. Normalizar contra os outros nove da
             * partida faria a mesma atuação mudar de forma conforme o lobby, e
             * o radar existe justamente para reconhecer o papel de relance.
             */
            const EIXOS_RADAR = [
              {
                nome: "Mira",
                // Ambas já são 0–100.
                calc: m => media([m.headshotPercentage, m.crosshairPlacementScore]),
              },
              {
                nome: "Impacto",
                calc: m => media([teto(m.adr, 120), m.openingDuelWinRate]),
              },
              {
                // Deliberadamente na posição de BAIXO: é o rótulo mais longo, e
                // ali a âncora é central, com espaço para os dois lados. Nas
                // laterais ele vazaria do viewBox e invadiria a coluna vizinha.
                nome: "Sobrevivência",
                // Menos mortes por round é melhor, então o eixo é invertido.
                // 1.0 morte/round é morrer em todo round — o pior caso real.
                calc: m => typeof m.deathsPerRound === "number"
                  ? Math.max(0, 100 - teto(m.deathsPerRound, 1.0)) : null,
              },
              {
                nome: "Suporte",
                calc: m => media([m.flashEfficiency, teto(m.utilityDamagePerRound, 20)]),
              },
            ];

            const teto = (v, max) =>
              typeof v === "number" ? Math.min(100, (v / max) * 100) : null;

            const media = vs => {
              const ok = vs.filter(v => typeof v === "number");
              // Eixo sem nenhuma métrica medida fica sem valor, em vez de zero:
              // zero desenharia "péssimo" onde não houve medição.
              return ok.length ? ok.reduce((a, b) => a + b, 0) / ok.length : null;
            };

            /**
             * Radar de papel na partida.
             *
             * Recebe o mapa achatado de métricas do jogador. Quatro eixos, então
             * o polígono é um losango — com poucos eixos a forma fica legível de
             * relance, que é o objetivo.
             */
            function RadarChart(metricas) {
              const valores = EIXOS_RADAR.map(e => e.calc(metricas || {}));
              if (valores.every(v => v === null)) return "";

              // Área mais larga que alta: o losango é simétrico, mas os rótulos
              // laterais ocupam espaço só na horizontal. Reservá-lo aqui é o
              // que impede o texto de sair do viewBox.
              const W = 300, H = 250, CX = W / 2, CY = H / 2 - 8, R = 70;
              const ang = i => (Math.PI * 2 * i) / EIXOS_RADAR.length - Math.PI / 2;
              const ponto = (i, r) =>
                [CX + Math.cos(ang(i)) * r, CY + Math.sin(ang(i)) * r];

              // Eixo sem medição usa o centro para o polígono fechar, mas não
              // ganha marcador — a diferença entre "zero" e "não medido"
              // aparece no rótulo, não na forma.
              const vertices = valores.map((v, i) =>
                ponto(i, ((typeof v === "number" ? v : 0) / 100) * R)
                  .map(n => n.toFixed(1)).join(",")).join(" ");

              // Dois anéis: 50% e 100%. Mais que isso vira grade e compete com
              // o polígono, que é o dado.
              const aneis = [0.5, 1].map(f => {
                const p = EIXOS_RADAR.map((_, i) =>
                  ponto(i, R * f).map(n => n.toFixed(1)).join(",")).join(" ");
                return `<polygon points="${p}" fill="none" stroke="var(--line)" stroke-width="1"/>`;
              }).join("");

              const raios = EIXOS_RADAR.map((_, i) => {
                const [x, y] = ponto(i, R);
                return `<line x1="${CX}" y1="${CY}" x2="${x.toFixed(1)}" y2="${y.toFixed(1)}"
                              stroke="var(--line)" stroke-width="1"/>`;
              }).join("");

              // Nome e valor em DUAS linhas. Na mesma linha, "Sobrevivência 45%"
              // fica largo demais e encosta no vizinho antes de o viewBox
              // acabar.
              const rotulos = EIXOS_RADAR.map((e, i) => {
                const [x, y] = ponto(i, R + 16);
                const v = valores[i];
                const centro = Math.abs(x - CX) < 2;
                const ancora = centro ? "middle" : (x > CX ? "start" : "end");
                const valorTxt = typeof v === "number" ? Math.round(v) + "%" : "—";

                // Rótulo de baixo desce um pouco mais para não tocar o vértice.
                const dy = centro ? (y > CY ? 10 : -4) : 0;

                return `<text x="${x.toFixed(1)}" y="${(y + dy).toFixed(1)}"
                              text-anchor="${ancora}" class="radar-lbl">
                    <tspan x="${x.toFixed(1)}">${esc(e.nome)}</tspan>
                    <tspan class="radar-val" x="${x.toFixed(1)}" dy="14">${valorTxt}</tspan>
                  </text>`;
              }).join("");

              const marcas = valores.map((v, i) => {
                if (typeof v !== "number") return "";
                const [x, y] = ponto(i, (v / 100) * R);
                return `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="3"
                                fill="var(--neon)"/>`;
              }).join("");

              return `<div class="radar">
                  <svg viewBox="0 0 ${W} ${H}" role="img"
                       aria-label="Perfil da partida por área de atuação">
                    ${aneis}${raios}
                    <polygon points="${vertices}" fill="rgba(176,38,255,.22)"
                             stroke="var(--neon)" stroke-width="2"/>
                    ${marcas}${rotulos}
                  </svg>
                </div>`;
            }

            // ═══════════════════════════════════════════════════════════
            //  WeaponTable
            // ═══════════════════════════════════════════════════════════

            /**
             * Nomes legíveis das armas.
             *
             * O parser grava o identificador da Valve ("m4a1_silencer"), que é
             * estável e serve de chave — mas ninguém chama a arma assim. As que
             * não estiverem aqui caem no próprio id, o que é feio mas correto:
             * melhor mostrar "negev" do que esconder a linha.
             */
            const NOMES_ARMA = {
              ak47:"AK-47", m4a1:"M4A4", m4a1_silencer:"M4A1-S", awp:"AWP",
              galilar:"Galil", famas:"FAMAS", sg556:"SG 553", aug:"AUG",
              ssg08:"SSG 08", scar20:"SCAR-20", g3sg1:"G3SG1",
              deagle:"Desert Eagle", glock:"Glock-18", usp_silencer:"USP-S",
              hkp2000:"P2000", p250:"P250", fiveseven:"Five-SeveN",
              tec9:"Tec-9", cz75a:"CZ75-Auto", elite:"Dual Berettas",
              revolver:"R8 Revolver",
              mp9:"MP9", mp7:"MP7", mp5sd:"MP5-SD", mac10:"MAC-10",
              ump45:"UMP-45", p90:"P90", bizon:"PP-Bizon",
              nova:"Nova", xm1014:"XM1014", mag7:"MAG-7", sawedoff:"Sawed-Off",
              m249:"M249", negev:"Negev",
              hegrenade:"Granada HE", molotov:"Molotov", incgrenade:"Incendiária",
              knife:"Faca", taser:"Zeus", c4:"C4", world:"Queda",
            };

            const nomeArma = id => NOMES_ARMA[id] || id;

            /**
             * Tabela de desempenho por arma.
             *
             * Ordenada por kills — a arma que mais matou descreve como o
             * jogador jogou a partida. Precisão e headshot só aparecem quando
             * há amostra; um traço é mais honesto que "0%" ou "100%" vindos de
             * três tiros.
             */
            function WeaponTable(armas) {
              const lista = (armas || []).filter(a => a.kills > 0 || a.tiros > 0);
              if (!lista.length) {
                return `<p class="hint">Sem uso de arma registrado nesta partida.</p>`;
              }

              const linhas = lista.map(a => {
                const hs = typeof a.headshotPercentage === "number"
                  ? a.headshotPercentage.toFixed(0) + "%" : "—";
                const acc = typeof a.accuracy === "number"
                  ? a.accuracy.toFixed(0) + "%" : "—";
                return `<tr>
                    <td>${esc(nomeArma(a.id))}</td>
                    <td>${a.kills}</td>
                    <td>${esc(hs)}</td>
                    <td>${a.damage}</td>
                    <td>${a.tiros || "—"}</td>
                    <td>${esc(acc)}</td>
                  </tr>`;
              }).join("");

              return `<div class="scroll"><table class="armas">
                  <thead><tr>
                    <th scope="col">Arma</th>
                    <th scope="col">Kills</th>
                    <th scope="col">HS</th>
                    <th scope="col">Dano</th>
                    <th scope="col">Tiros</th>
                    <th scope="col" title="Disparos que causaram dano. Só aparece com tiros suficientes.">Acerto</th>
                  </tr></thead>
                  <tbody>${linhas}</tbody>
                </table></div>`;
            }

            // ═══════════════════════════════════════════════════════════
            //  RoundHighlights
            // ═══════════════════════════════════════════════════════════

            /**
             * Os melhores rounds do jogador.
             *
             * O placar da partida conta o agregado; isto conta o momento —
             * "no round 14 você fechou sozinho contra três". A pontuação fica
             * visível porque é o critério da ordem: sem ela, a lista pareceria
             * arbitrária.
             *
             * Lista vazia é resultado legítimo e é dito com todas as letras.
             * Inventar um destaque de uma kill para não deixar a seção vazia
             * seria elogiar o que não houve.
             */
            function RoundHighlights(destaques) {
              const lista = destaques || [];
              if (!lista.length) {
                return `<p class="hint">Nenhum round se destacou nesta partida.</p>`;
              }

              const itens = lista.map((d, i) => {
                const classes = ["hl-item"];
                if (i === 0) classes.push("is-topo");
                classes.push(d.venceuRound ? "is-ganho" : "is-perdido");

                return `<li class="${classes.join(" ")}">
                    <div class="hl-round">Round<b>${d.roundNumber}</b></div>
                    <div class="hl-corpo">
                      <div class="hl-titulo">${esc(d.titulo)}</div>
                      <p class="hl-desc">${esc(d.descricao)}</p>
                    </div>
                    <div class="hl-pts" title="Pontuação do round. A unidade é a kill: 1,0 = uma kill.">
                      <b>${d.pontuacao.toFixed(1)}</b>pts
                    </div>
                  </li>`;
              }).join("");

              return `<ul class="hl">${itens}</ul>`;
            }

            // ═══════════════════════════════════════════════════════════
            //  Sparkline
            // ═══════════════════════════════════════════════════════════

            /**
             * Micro-gráfico para o rodapé de um MetricCard.
             *
             * Sem eixo, sem rótulo, sem ponto — só a forma da curva. O número
             * do card já diz quanto; a sparkline responde "e isso está subindo
             * ou descendo?", que é a pergunta seguinte. Qualquer ornamento aqui
             * competiria com o número, que é o elemento principal do card.
             *
             * O último ponto ganha uma marca, porque é o valor que o card está
             * exibindo — sem ela a curva parece desligada do número.
             */
            function Sparkline(valores, maiorEhMelhor) {
              const v = (valores || []).filter(n => typeof n === "number");
              if (v.length < 2) return "";

              const W = 100, H = 24, P = 2;
              let min = Math.min(...v), max = Math.max(...v);
              if (min === max) { min -= 1; max += 1; }

              const x = i => P + (i * (W - 2 * P)) / (v.length - 1);
              const y = n => P + (H - 2 * P) * (1 - (n - min) / (max - min));

              const pontos = v.map((n, i) => `${x(i).toFixed(1)},${y(n).toFixed(1)}`).join(" ");

              // A cor segue a direção da métrica: em mortes por round, a curva
              // subindo é notícia ruim.
              const subiu = v[v.length - 1] > v[0];
              const bom = maiorEhMelhor === false ? !subiu : subiu;
              const cor = v[v.length - 1] === v[0] ? "var(--muted)"
                        : (bom ? "var(--good)" : "var(--bad)");

              return `<svg class="spark" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"
                           aria-hidden="true">
                  <polyline points="${pontos}" fill="none" stroke="${cor}"
                            stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                  <circle cx="${x(v.length - 1).toFixed(1)}" cy="${y(v[v.length - 1]).toFixed(1)}"
                          r="2" fill="${cor}"/>
                </svg>`;
            }

            // ═══════════════════════════════════════════════════════════
            //  KdBar
            // ═══════════════════════════════════════════════════════════

            /**
             * Proporção de kills e mortes como barra.
             *
             * "2.25" exige converter mentalmente para saber se foram 18/8 ou
             * 45/20. A barra mostra o volume junto da razão, e as duas metades
             * carregam o próprio número — a cor sozinha não diria qual é qual
             * para quem não distingue verde de vermelho.
             */
            function KdBar(kills, mortes) {
              const k = Number(kills) || 0, d = Number(mortes) || 0;
              const total = k + d;
              if (!total) return "";

              const pk = (k / total) * 100;

              return `<div class="kdbar" role="img"
                           aria-label="${k} kills contra ${d} mortes">
                  <div class="kdbar-track">
                    <span class="kdbar-k" data-largura="${pk.toFixed(1)}%" style="width:0"></span>
                  </div>
                  <div class="kdbar-legend">
                    <span class="up">${k} kills</span>
                    <span class="down">${d} mortes</span>
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

              // A bolinha carrega o resultado da partida. Saber que o K/D foi
              // 1.5 é uma informação; saber que foi 1.5 numa DERROTA é a que
              // muda o que o jogador vai investigar.
              const bolinhas = pts.map((p, i) => {
                if (typeof p.valor !== "number") return "";
                const cor = p.won === true ? "var(--good)"
                          : (p.won === false ? "var(--bad)" : "var(--neon)");
                return `<circle class="tp" cx="${x(i).toFixed(1)}" cy="${y(p.valor).toFixed(1)}"
                          r="4" fill="var(--bg)" stroke="${cor}" stroke-width="2"
                          tabindex="0" data-i="${i}"/>`;
              }).join("");

              // Ponto ausente vira marca vazada na base, com rótulo. Deixar só
              // o buraco faz parecer defeito de renderização; preencher com
              // zero afirmaria um desempenho que não foi medido.
              const ausentes = pts.map((p, i) => {
                if (typeof p.valor === "number") return "";
                return `<g class="gap">
                    <line x1="${x(i).toFixed(1)}" x2="${x(i).toFixed(1)}"
                          y1="${PAD_Y}" y2="${H - PAD_Y}"
                          stroke="var(--line)" stroke-width="1" stroke-dasharray="2 4"/>
                    <circle class="tp" cx="${x(i).toFixed(1)}" cy="${H - PAD_Y}" r="3.5"
                            fill="none" stroke="var(--muted)" stroke-width="1.5"
                            stroke-dasharray="2 2" tabindex="0" data-i="${i}"/>
                  </g>`;
              }).join("");

              const linhaRef = (v, cor, titulo) =>
                (typeof v === "number" && v >= min && v <= max)
                  ? `<line x1="${PAD_X}" x2="${W - PAD_X}" y1="${y(v).toFixed(1)}"
                           y2="${y(v).toFixed(1)}" stroke="${cor}"
                           stroke-width="1" stroke-dasharray="4 4"><title>${esc(titulo)}</title></line>`
                  : "";

              // Duas referências com pesos diferentes: a sua média é a que se
              // compara consigo mesmo; a da faixa diz se já está acima do
              // esperado para o nível.
              //
              // A linha da faixa usa a COR da faixa, como todo lugar que a
              // exibe — assim ela se identifica sozinha, sem depender de o
              // usuário abrir o tooltip para saber o que é aquela segunda
              // linha. Fica com opacidade menor para não competir com a linha
              // do próprio desempenho.
              const media = linhaRef(serie.media, "var(--line)", "Sua média")
                + linhaRef(serie.mediaDaFaixa, "var(--tier,rgba(139,127,168,.35))",
                           "Média da faixa " + (serie.faixaLabel || ""));

              // Primeiro vs. último ponto medido: é a leitura que o jogador quer
              // ("estou melhorando?"), e ela respeita a direção da métrica.
              const ini = valores[0], fim = valores[valores.length - 1];
              const delta = fim - ini;
              const melhorou = serie.maiorEhMelhor ? delta > 0 : delta < 0;
              const neutro = Math.abs(delta) < 1e-9;
              const cls = neutro ? "dim" : (melhorou ? "up" : "down");
              const seta = neutro ? "—" : (delta > 0 ? "▲" : "▼");

              // Âncoras de tempo. Só as pontas e o meio: uma data por ponto
              // viraria uma fileira ilegível, e o que o cérebro precisa é da
              // janela ("isso é de um mês ou de ontem?").
              const dataCurta = p => p.playedAt
                ? new Date(p.playedAt).toLocaleDateString("pt-BR", {day:"2-digit", month:"2-digit"})
                : "";
              const ultimo = pts.length - 1;
              const meio = Math.floor(ultimo / 2);
              const eixoX = [0, meio, ultimo]
                .filter((v, i, a) => a.indexOf(v) === i)
                .map(i => {
                  const ancora = i === 0 ? "start" : (i === ultimo ? "end" : "middle");
                  const rotulo = i === ultimo ? "mais recente" : dataCurta(pts[i]);
                  return `<text x="${x(i).toFixed(1)}" y="${H - 2}" text-anchor="${ancora}"
                                class="eixo">${esc(rotulo)}</text>`;
                }).join("");

              const legendaFaixa = typeof serie.mediaDaFaixa === "number"
                ? `<span class="trend-faixa">· ${esc(nomeTier(serie.faixaTier) || "faixa")} `
                  + `${esc(serie.mediaDaFaixa.toFixed(1))}</span>`
                : "";

              // A classe da faixa entra no container para que --tier valha para
              // a linha de referência e para o rodapé.
              return `
                <div class="trend${classeTier(serie.faixaTier)}"
                     data-serie='${esc(JSON.stringify(pts))}'>
                  <div class="trend-head">
                    <span class="trend-label">${esc(serie.label)}</span>
                    <span class="${cls}"><span class="sig" aria-hidden="true">${seta}</span>${
                      esc((delta >= 0 ? "+" : "") + delta.toFixed(2))}</span>
                    <span class="dim">nas últimas ${pts.length} partidas</span>
                  </div>
                  <div class="trend-plot">
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
                      ${media}${area}${linhas}${ausentes}${bolinhas}${eixoX}
                    </svg>
                    <div class="tip" hidden></div>
                  </div>
                  <div class="trend-foot">
                    <span class="dim mono">${esc(min.toFixed(1))}</span>
                    <span class="dim">sua média ${esc(typeof serie.media === "number" ? serie.media.toFixed(1) : "—")}
                      ${legendaFaixa}</span>
                    <span class="dim mono">${esc(max.toFixed(1))}</span>
                  </div>
                </div>`;
            }

            /**
             * Liga o tooltip do gráfico depois de ele entrar no DOM.
             *
             * Um listener no container em vez de um por bolinha: são até
             * cinquenta pontos, e delegar mantém um handler só. O balão é
             * posicionado em % da largura, então acompanha o SVG que estica.
             */
            function ativarTooltip(container) {
              const raiz = container.querySelector(".trend");
              if (!raiz) return;

              const plot = raiz.querySelector(".trend-plot");
              const tip = raiz.querySelector(".tip");
              const pts = JSON.parse(raiz.dataset.serie || "[]");

              const mostrar = alvo => {
                const p = pts[Number(alvo.dataset.i)];
                if (!p) return;

                const data = p.playedAt
                  ? new Date(p.playedAt).toLocaleDateString("pt-BR",
                      {day:"2-digit", month:"2-digit", year:"numeric"})
                  : "";

                let resultado = "";
                if (p.won === true || p.won === false) {
                  const placar = (p.scoreSelf != null && p.scoreEnemy != null)
                    ? ` ${p.scoreSelf}-${p.scoreEnemy}` : "";
                  resultado = `<span class="${p.won ? "up" : "down"}">${
                    p.won ? "Vitória" : "Derrota"}${esc(placar)}</span>`;
                }

                const valor = typeof p.valor === "number"
                  ? `<b>${esc(p.valor)}</b>`
                  : `<span class="dim">não medido nesta partida</span>`;

                tip.innerHTML = `${valor}<span class="dim">${esc(p.mapName || "")}</span>`
                              + `${resultado}<span class="dim">${esc(data)}</span>`;

                // A bolinha vive num viewBox de largura fixa; converter para %
                // faz o balão seguir o SVG em qualquer largura de tela.
                const cx = Number(alvo.getAttribute("cx"));
                tip.style.left = (cx / 640 * 100) + "%";
                tip.hidden = false;
              };

              const esconder = () => { tip.hidden = true; };

              plot.addEventListener("pointerover", e => {
                if (e.target.classList.contains("tp")) mostrar(e.target);
              });
              plot.addEventListener("pointerleave", esconder);
              // Teclado: as bolinhas são focáveis, então o mesmo balão serve.
              plot.addEventListener("focusin", e => {
                if (e.target.classList.contains("tp")) mostrar(e.target);
              });
              plot.addEventListener("focusout", esconder);
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
             * Promove o alerta mais grave para o topo da página.
             *
             * O painel completo fica no fim da rolagem, e quem abre o relatório
             * pelo link da Steam costuma ler o placar e sair. Trazer UM aviso
             * para cima resolve isso sem transformar a página numa parede de
             * alertas — mais de um banner deixa de ser destaque.
             *
             * Só AVISO sobe: elogio não precisa interromper a leitura.
             */
            function CoachBanner(insights) {
              const aviso = (insights || []).find(i => i.gravidade === "AVISO");
              if (!aviso) return "";

              return `<div class="coach-banner">
                  ${icon("aviso", 20)}
                  <div>
                    <p>${esc(aviso.texto)}</p>
                    <a href="#coachTitle">Ver tudo o que treinar</a>
                  </div>
                </div>`;
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

    /**
     * Vai dentro do {@code <script>}, antes do código específico da página.
     *
     * <p>O fundo 3D vem no fim: ele lê {@code MOVIMENTO_OK}, declarado acima,
     * e precisa que o {@code body} já exista quando roda.</p>
     */
    static final String JS = COMPONENTES + "\n" + HudBackdrop.JS;

}
