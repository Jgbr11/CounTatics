package com.countatic.core.controller;

/**
 * Lista navegável das partidas de um jogador ({@code /p/{token}/partidas}).
 *
 * <p>Reaproveita o mesmo payload do painel — o serviço já devolve as partidas
 * com resultado, placar orientado pelo lado e métricas básicas. Uma consulta
 * própria só para listar duplicaria essa orientação sem entregar nada novo.</p>
 *
 * <p>O filtro por mapa roda <b>no navegador</b>. Com a janela de cem partidas
 * que o serviço já traz, filtrar no servidor significaria uma ida e volta para
 * esconder linhas que já estão na tela.</p>
 */
final class MatchListPageTemplate {

    private MatchListPageTemplate() {
    }

    static String render(String dashboardJson, String nav) {
        String safeJson = dashboardJson.replace("</", "<\\/");
        return HTML
                .replace("__NAV__", nav)
                .replace("__DASH_DATA__", safeJson);
    }

    private static final String PAGE_CSS = """
            h1{margin-bottom:.4rem}
            .sub{color:var(--muted);font-size:.85rem;margin:0 0 1.5rem}

            .filtros{display:flex;gap:.35rem;flex-wrap:wrap;margin-bottom:1.1rem}
            .filtros .btn{padding:.35rem .75rem;font-size:.68rem}
            .filtros .btn[aria-pressed="true"]{border-color:var(--neon);
                                               background:rgba(176,38,255,.16)}

            .scroll{overflow-x:auto;-webkit-overflow-scrolling:touch}
            table{width:100%;border-collapse:collapse;min-width:680px}
            thead th{font-family:var(--f-body);font-size:.68rem;font-weight:600;
                     letter-spacing:.14em;text-transform:uppercase;color:var(--muted);
                     text-align:right;padding:.5rem .6rem;
                     border-bottom:1px solid var(--line);white-space:nowrap}
            thead th:first-child,thead th:nth-child(2){text-align:left}
            tbody td{padding:.65rem .6rem;text-align:right;white-space:nowrap;
                     font-family:var(--f-mono);font-size:.9rem;
                     border-bottom:1px solid rgba(176,38,255,.12)}
            tbody td:first-child,tbody td:nth-child(2){text-align:left}
            tbody tr{--st:var(--neon-dim)}
            tbody tr.is-win {--st:var(--good)}
            tbody tr.is-loss{--st:var(--bad)}
            tbody td:first-child{box-shadow:inset 2px 0 0 var(--st)}
            tbody tr:hover{background:rgba(176,38,255,.07)}
            tbody tr:last-child td{border-bottom:none}
            tbody a{color:var(--text);text-decoration:none;
                    font-family:var(--f-body);font-weight:600}
            tbody a:hover{color:var(--neon);text-decoration:underline}

            .hint{color:var(--muted);font-size:.82rem;margin:.9rem 0 0}
            footer{position:relative;z-index:2;text-align:center;color:var(--muted);
                   font-size:.76rem;letter-spacing:.08em;padding-bottom:2.5rem}
            """;

    private static final String HTML = ("""
            <!doctype html>
            <html lang="pt-BR">
            <head>
            __META__
              <title>Partidas — CounTatic</title>
            __FONTS__
              <style>
            __THEME_CSS__
            __PAGE_CSS__
              </style>
            </head>
            <body>
            __NAV__
            <div class="wrap">

              <h1 id="titulo">Partidas</h1>
              <p class="sub" id="sub"></p>

              <div class="filtros" id="filtros" role="group" aria-label="Filtrar por mapa"></div>

              <section class="panel cut">
                <div class="cut-in">
                  <div class="scroll">
                    <table>
                      <thead><tr>
                        <th scope="col">Resultado</th>
                        <th scope="col">Mapa</th>
                        <th scope="col">K</th>
                        <th scope="col">D</th>
                        <th scope="col">K/D</th>
                        <th scope="col">ADR</th>
                        <th scope="col">Quando</th>
                      </tr></thead>
                      <tbody id="rows"></tbody>
                    </table>
                  </div>
                  <p class="hint" id="vazio" hidden>Nenhuma partida neste mapa.</p>
                </div>
              </section>

            </div>
            <footer>Clique no mapa para abrir o relatório completo</footer>

            <script>
            __COMPONENTS_JS__

            const DASH = __DASH_DATA__;

            const el = id => document.getElementById(id);
            const txt = s => { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; };

            /** "" = todos os mapas. */
            let mapaAtual = "";

            function renderFiltros() {
              // Só os mapas que a pessoa realmente jogou: uma lista fixa dos
              // sete oficiais mostraria botões que não filtram nada.
              const mapas = [...new Set((DASH.partidas || [])
                .map(p => p.mapName).filter(Boolean))].sort();

              el("filtros").innerHTML =
                [["", "Todos"]].concat(mapas.map(m => [m, m]))
                  .map(([valor, rotulo]) =>
                    `<button type="button" class="btn" data-mapa="${txt(valor)}"
                             aria-pressed="${valor === mapaAtual}">${txt(rotulo)}</button>`)
                  .join("");

              el("filtros").querySelectorAll("button").forEach(b => {
                b.onclick = () => { mapaAtual = b.dataset.mapa || ""; renderFiltros(); renderLista(); };
              });
            }

            function renderLista() {
              const todas = DASH.partidas || [];
              const lista = mapaAtual ? todas.filter(p => p.mapName === mapaAtual) : todas;

              el("sub").textContent = mapaAtual
                ? `${lista.length} partida(s) em ${mapaAtual}`
                : `${lista.length} partida(s) analisada(s)`;

              el("vazio").hidden = lista.length > 0;

              const tb = el("rows");
              tb.innerHTML = "";

              lista.forEach(p => {
                const tr = document.createElement("tr");
                tr.className = p.won === true ? "is-win" : (p.won === false ? "is-loss" : "");

                const placar = (p.scoreSelf != null && p.scoreEnemy != null)
                  ? `${p.scoreSelf}-${p.scoreEnemy}` : "—";
                const rotulo = p.won === true ? "Vitória" : (p.won === false ? "Derrota" : "—");
                const cor = p.won === true ? "up" : (p.won === false ? "down" : "dim");
                const quando = p.playedAt ? new Date(p.playedAt).toLocaleDateString("pt-BR") : "";
                const kd = typeof p.kdRatio === "number" ? p.kdRatio.toFixed(2) : "—";
                const adr = typeof p.adr === "number" ? p.adr.toFixed(1) : "—";

                tr.innerHTML =
                  `<td class="${cor}">${txt(rotulo)} ${txt(placar)}</td>` +
                  `<td>${p.publicToken
                        ? `<a href="/m/${encodeURIComponent(p.publicToken)}">${txt(p.mapName)}</a>`
                        : txt(p.mapName)}</td>` +
                  `<td>${p.kills ?? "—"}</td><td>${p.deaths ?? "—"}</td>` +
                  `<td>${txt(kd)}</td><td>${txt(adr)}</td>` +
                  `<td class="dim">${txt(quando)}</td>`;

                tb.appendChild(tr);
              });

              Motion.entrar(tb, "tr");
            }

            oferecerVoltaAPartida();

            el("titulo").textContent = "Partidas de " + (DASH.playerName || DASH.steamId64);
            Motion.revelar(".panel");

            renderFiltros();
            renderLista();
            </script>
            </body>
            </html>
            """)
            .replace("__META__", HudTheme.META)
            .replace("__FONTS__", HudTheme.FONTS)
            .replace("__THEME_CSS__", HudTheme.CSS)
            .replace("__PAGE_CSS__", PAGE_CSS)
            .replace("__COMPONENTS_JS__", HudComponents.JS);
}
