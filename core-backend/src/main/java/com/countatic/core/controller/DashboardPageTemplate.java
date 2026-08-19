package com.countatic.core.controller;

/**
 * Template HTML do painel do jogador ({@code /p/{token}}).
 *
 * <p>Mesma montagem da página da partida: HTML autocontido com os dados
 * embutidos como JSON, para não pagar CORS nem uma segunda requisição. O
 * vocabulário visual vem de {@link HudTheme} e os componentes de
 * {@link HudComponents}; aqui ficam só o cabeçalho, o retrospecto e a lista de
 * partidas.</p>
 */
final class DashboardPageTemplate {

    private DashboardPageTemplate() {
    }

    static String render(String dashboardJson) {
        // Mesma proteção da página da partida: um nome de jogador contendo
        // "</script>" fecharia a tag mais cedo e o resto do JSON viraria HTML.
        String safeJson = dashboardJson.replace("</", "<\\/");
        return HTML.replace("__DASH_DATA__", safeJson);
    }

    private static final String PAGE_CSS = """
            .hdr{display:flex;align-items:center;gap:1.5rem;flex-wrap:wrap;margin-bottom:1.75rem}
            .hdr-main{flex:1 1 320px;min-width:0}
            .brand{font-family:var(--f-mono);font-size:.72rem;letter-spacing:.22em;
                   text-transform:uppercase;color:var(--neon);margin-bottom:.55rem}
            .brand span{color:var(--muted)}

            /* Retrospecto: vitórias em verde, derrotas em vermelho, e o que não
               foi registrado fica visível em vez de somado a um dos lados. */
            .record{display:flex;align-items:baseline;gap:.5rem;margin-top:.9rem;
                    font-family:var(--f-display);font-weight:900;font-size:1.9rem}
            .record .sep{color:var(--muted);font-size:1.1rem}
            .record .obs{font-family:var(--f-body);font-weight:600;font-size:.72rem;
                         letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}

            .scroll{overflow-x:auto;-webkit-overflow-scrolling:touch}
            table{width:100%;border-collapse:collapse;min-width:640px}
            thead th{font-family:var(--f-body);font-size:.68rem;font-weight:600;
                     letter-spacing:.14em;text-transform:uppercase;color:var(--muted);
                     text-align:right;padding:.5rem .6rem;
                     border-bottom:1px solid var(--line);white-space:nowrap}
            thead th:first-child,thead th:nth-child(2){text-align:left}
            tbody td{padding:.6rem;text-align:right;white-space:nowrap;
                     font-family:var(--f-mono);font-size:.9rem;
                     border-bottom:1px solid rgba(176,38,255,.12)}
            tbody td:first-child,tbody td:nth-child(2){text-align:left}
            tbody tr{--st:var(--neon-dim);transition:background .15s}
            tbody tr.is-win {--st:var(--good)}
            tbody tr.is-loss{--st:var(--bad)}
            tbody td:first-child{box-shadow:inset 2px 0 0 var(--st)}
            tbody tr:hover{background:rgba(176,38,255,.07)}
            tbody tr:last-child td{border-bottom:none}
            tbody a{color:var(--text);text-decoration:none;font-family:var(--f-body);font-weight:600}
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
              <title>Painel do Jogador — CounTatic</title>
            __FONTS__
              <style>
            __THEME_CSS__
            __PAGE_CSS__
              </style>
            </head>
            <body>
            <div class="wrap">

              <header class="hdr">
                <div class="hdr-main">
                  <div class="brand">CounTatic <span>// Painel</span></div>
                  <h1 id="nome">Jogador</h1>
                  <div class="record" id="record"></div>
                  <div class="meta" id="meta"></div>
                </div>
                <div id="rank"></div>
              </header>

              <section class="panel cut">
                <div class="cut-in">
                  <div class="panel-head"><h2 id="mediasTitle">Médias</h2></div>
                  <p class="hint" style="margin:-.5rem 0 1rem">
                    A cor compara com a <b>média da sua faixa</b>:
                    <span class="up">▲ acima</span> ·
                    <span class="dim">— na média</span> ·
                    <span class="down">▼ abaixo</span>.
                    Sem amostra suficiente na faixa, o número fica neutro.
                  </p>
                  <div id="medias"></div>
                </div>
              </section>

              <section class="panel cut">
                <div class="cut-in">
                  <div class="panel-head"><h2>Evolução</h2></div>
                  <div class="trend-tabs" id="trendTabs" role="group"
                       aria-label="Métrica do gráfico de evolução"></div>
                  <div id="trend" aria-live="polite"></div>
                </div>
              </section>

              <section class="panel cut">
                <div class="cut-in">
                  <div class="panel-head"><h2>Partidas</h2></div>
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
                  <p class="hint">Clique no mapa para abrir o relatório completo da partida.</p>
                </div>
              </section>

            </div>
            <footer>Médias das últimas partidas analisadas</footer>

            <script>
            __COMPONENTS_JS__

            const DASH = __DASH_DATA__;

            const el = id => document.getElementById(id);
            const txt = s => { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; };

            /**
             * Config das métricas do painel.
             *
             * Só as dez comparáveis aparecem aqui — são as que o backend
             * agrega. As absolutas (kills, flashes lançadas) não fazem média
             * entre partidas de tamanhos diferentes.
             */
            const METRICAS = {
              adr:                     {rotulo:"ADR", fmt:v=>v.toFixed(1), icone:"dano", principal:true},
              kdRatio:                 {rotulo:"K/D", fmt:v=>v.toFixed(2), icone:"mira", principal:true},
              headshotPercentage:      {rotulo:"Headshot %", fmt:v=>v.toFixed(1)+"%", icone:"mira"},
              killsPerRound:           {rotulo:"Kills / round", fmt:v=>v.toFixed(2), icone:"mira"},
              deathsPerRound:          {rotulo:"Mortes / round", fmt:v=>v.toFixed(2), icone:"morte", dir:"menor"},
              tradeKillsPerRound:      {rotulo:"Trades / round", fmt:v=>v.toFixed(2), icone:"duelo"},
              openingDuelWinRate:      {rotulo:"Taxa 1º duelo", fmt:v=>v.toFixed(1)+"%", icone:"duelo"},
              flashEfficiency:         {rotulo:"Eficiência de flash", fmt:v=>v.toFixed(1)+"%", icone:"granada"},
              utilityDamagePerRound:   {rotulo:"Dano util. / round", fmt:v=>v.toFixed(1), icone:"dano"},
              crosshairPlacementScore: {rotulo:"Crosshair placement", fmt:v=>v.toFixed(1)+"%", icone:"mira"},
              closeRangeWinRate:       {rotulo:"Duelos curtos", fmt:v=>v.toFixed(0)+"%", icone:"duelo"},
              longRangeWinRate:        {rotulo:"Duelos longos", fmt:v=>v.toFixed(0)+"%", icone:"duelo"},
              earlyDeathRate:          {rotulo:"Mortes na entrada", fmt:v=>v.toFixed(0)+"%", icone:"relogio", dir:"menor"},
            };

            const cfg = k => METRICAS[k] || {rotulo:k, fmt:v=>v.toFixed(2), dir:"neutro"};

            /** Mesma zona morta relativa da página da partida. */
            const ZONA_MORTA = 0.05;

            function status(chave, valor) {
              const ref = (DASH.mediasDaFaixa || {})[chave];
              const dir = cfg(chave).dir || "maior";
              if (dir === "neutro" || typeof ref !== "number") return "neutro";

              const escala = Math.abs(ref) || 1;
              const delta = (valor - ref) / escala;
              if (Math.abs(delta) <= ZONA_MORTA) return "neutro";

              const acima = delta > 0;
              return (dir === "menor" ? !acima : acima) ? "acima" : "abaixo";
            }

            function renderCabecalho() {
              el("nome").textContent = DASH.playerName || DASH.steamId64;

              const obs = DASH.resultadoDesconhecido > 0
                ? `<span class="obs">+${DASH.resultadoDesconhecido} sem resultado registrado</span>`
                : "";
              el("record").innerHTML =
                `<span class="up">${DASH.vitorias}</span><span class="sep">V</span>` +
                `<span class="down">${DASH.derrotas}</span><span class="sep">D</span>${obs}`;

              const tag = (l, v, extra) =>
                `<span class="tag${extra || ""}">${txt(l)} <b>${txt(v)}</b></span>`;
              const bits = [tag("Partidas", DASH.partidasAnalisadas)];
              if (DASH.rankTierLabel) {
                bits.push(tag("Faixa", DASH.rankTierLabel, " is-tier" + classeTier(DASH.rankTier)));
              }
              el("meta").innerHTML = bits.join("");

              if (DASH.csRating != null) {
                el("rank").innerHTML =
                  `<div class="badge${classeTier(DASH.rankTier)}">` +
                  `<div><div class="n">${txt(DASH.csRating)}</div>` +
                  `<div class="t">${txt(nomeTier(DASH.rankTier) || "Rating")}</div></div></div>`;
              }
            }

            function renderMedias() {
              const medias = DASH.medias || {};
              const chaves = Object.keys(METRICAS).filter(k => typeof medias[k] === "number");

              el("mediasTitle").textContent =
                `Médias das últimas ${DASH.partidasAnalisadas} partidas`;

              if (!chaves.length) {
                el("medias").innerHTML =
                  `<p class="hint">Nenhuma partida analisada ainda para este jogador.</p>`;
                return;
              }

              // Principais primeiro, senão o span de duas colunas abre buracos.
              chaves.sort((a, b) => (cfg(b).principal ? 1 : 0) - (cfg(a).principal ? 1 : 0));

              el("medias").innerHTML = `<div class="chips">` + chaves.map(k => {
                const c = cfg(k);
                return MetricCard({
                  label: c.rotulo,
                  valor: c.fmt(medias[k]),
                  icone: c.icone,
                  status: status(k, medias[k]),
                  principal: c.principal,
                });
              }).join("") + `</div>`;
            }

            function renderPartidas() {
              const tb = el("rows");
              tb.innerHTML = "";

              (DASH.partidas || []).forEach(p => {
                const tr = document.createElement("tr");
                tr.className = p.won === true ? "is-win" : (p.won === false ? "is-loss" : "");

                const placar = (p.scoreSelf != null && p.scoreEnemy != null)
                  ? `${p.scoreSelf}-${p.scoreEnemy}` : "—";
                const rotulo = p.won === true ? "Vitória"
                             : (p.won === false ? "Derrota" : "—");
                const cor = p.won === true ? "up" : (p.won === false ? "down" : "dim");
                const quando = p.playedAt
                  ? new Date(p.playedAt).toLocaleDateString("pt-BR") : "";

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
            }

            // ─── Evolução ────────────────────────────────────────────
            const TENDENCIAS = ["adr", "kdRatio", "headshotPercentage", "longRangeWinRate"];
            let metricaAtual = TENDENCIAS[0];

            function renderTendencia() {
              el("trendTabs").innerHTML = TENDENCIAS.map(k =>
                `<button type="button" class="btn" data-metrica="${k}"
                         aria-pressed="${k === metricaAtual}">${txt(cfg(k).rotulo)}</button>`
              ).join("");

              el("trendTabs").querySelectorAll("button").forEach(b => {
                b.onclick = () => { metricaAtual = b.dataset.metrica; renderTendencia(); };
              });

              carregarSerie(DASH.steamId64, metricaAtual);
            }

            async function carregarSerie(steamId, metrica) {
              const alvo = el("trend");
              alvo.innerHTML = `<p class="hint">Carregando…</p>`;

              const pedido = steamId + "|" + metrica;
              alvo.dataset.pedido = pedido;

              try {
                const r = await fetch(`/api/players/${encodeURIComponent(steamId)}/trend`
                                    + `?metric=${encodeURIComponent(metrica)}&limit=20`);
                if (!r.ok) throw new Error("HTTP " + r.status);
                const serie = await r.json();

                if (alvo.dataset.pedido !== pedido) return;
                alvo.innerHTML = TrendChart(serie);
                ativarTooltip(alvo);
              } catch (e) {
                if (alvo.dataset.pedido !== pedido) return;
                alvo.innerHTML = `<p class="hint">Não foi possível carregar a evolução agora.</p>`;
              }
            }

            renderCabecalho();
            renderMedias();
            renderPartidas();
            renderTendencia();
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
