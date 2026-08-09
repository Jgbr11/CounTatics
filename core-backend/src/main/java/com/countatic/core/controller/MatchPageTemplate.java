package com.countatic.core.controller;

/**
 * Template HTML da página de detalhes da partida.
 *
 * <p>Fica isolado do controller apenas por legibilidade — é uma string grande
 * e sem lógica de negócio. Os dados entram como JSON embutido em
 * {@code __MATCH_DATA__}.</p>
 */
final class MatchPageTemplate {

    private MatchPageTemplate() {
    }

    static String render(String matchJson) {
        // O JSON é gerado pelo Jackson, então já vem com aspas e barras
        // escapadas corretamente. O único risco em contexto <script> é a
        // sequência "</script>" aparecendo dentro de uma string (ex: num nome
        // de jogador). Quebrá-la neutraliza o fechamento precoce da tag.
        String safeJson = matchJson.replace("</", "<\\/");
        return HTML.replace("__MATCH_DATA__", safeJson);
    }

    private static final String HTML = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Relatório da Partida — CounTatic</title>
              <style>
                :root{
                  --bg:#0d1117; --panel:#161b22; --panel-2:#1c2129;
                  --border:#30363d; --text:#e6edf3; --muted:#8b949e;
                  --accent:#58a6ff; --good:#3fb950; --warn:#d29922; --bad:#f85149;
                }
                *{box-sizing:border-box}
                body{
                  margin:0;background:var(--bg);color:var(--text);
                  font:15px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
                  padding:1.5rem 1rem 4rem;
                }
                .wrap{max-width:1100px;margin:0 auto}

                header{margin-bottom:1.5rem}
                .brand{color:var(--accent);font-weight:600;letter-spacing:.02em;font-size:.85rem;
                       text-transform:uppercase;margin-bottom:.35rem}
                h1{margin:0 0 .5rem;font-size:1.8rem;letter-spacing:-.02em}
                .meta{display:flex;flex-wrap:wrap;gap:.4rem .9rem;color:var(--muted);font-size:.9rem}
                .meta b{color:var(--text);font-weight:600}

                .card{background:var(--panel);border:1px solid var(--border);
                      border-radius:12px;padding:1.1rem 1.2rem;margin-bottom:1.1rem}
                .card h2{margin:0 0 .9rem;font-size:1rem;font-weight:600;color:var(--muted);
                         text-transform:uppercase;letter-spacing:.04em}

                /* Tabelas largas rolam dentro do próprio container:
                   a página nunca rola na horizontal. */
                .scroll{overflow-x:auto;-webkit-overflow-scrolling:touch}
                table{width:100%;border-collapse:collapse;min-width:520px}
                th,td{padding:.55rem .6rem;text-align:right;white-space:nowrap}
                th:first-child,td:first-child{text-align:left;white-space:normal}
                thead th{color:var(--muted);font-weight:600;font-size:.78rem;
                         text-transform:uppercase;letter-spacing:.04em;
                         border-bottom:1px solid var(--border)}
                tbody tr{border-bottom:1px solid rgba(48,54,61,.5);cursor:pointer}
                tbody tr:hover{background:var(--panel-2)}
                tbody tr.sel{background:rgba(88,166,255,.09)}
                tbody tr:last-child{border-bottom:none}
                .nm{font-weight:600}

                .grid{display:grid;gap:.7rem;
                      grid-template-columns:repeat(auto-fill,minmax(190px,1fr))}
                .stat{background:var(--panel-2);border:1px solid var(--border);
                      border-radius:9px;padding:.7rem .8rem}
                .stat .k{color:var(--muted);font-size:.76rem;text-transform:uppercase;
                         letter-spacing:.03em;margin-bottom:.25rem}
                .stat .v{font-size:1.25rem;font-weight:650;font-variant-numeric:tabular-nums}

                .cat{margin-bottom:1.1rem}
                .cat > h3{margin:0 0 .55rem;font-size:.95rem;color:var(--accent)}

                .tips{list-style:none;padding:0;margin:0}
                .tips li{background:var(--panel-2);border-left:3px solid var(--warn);
                         border-radius:0 8px 8px 0;padding:.6rem .8rem;margin-bottom:.5rem;
                         font-size:.92rem}

                .hint{color:var(--muted);font-size:.85rem;margin:.2rem 0 0}
                footer{color:var(--muted);font-size:.8rem;text-align:center;margin-top:2rem}
              </style>
            </head>
            <body>
            <div class="wrap">
              <header>
                <div class="brand">CounTatic</div>
                <h1 id="title">Relatório da Partida</h1>
                <div class="meta" id="meta"></div>
              </header>

              <div class="card">
                <h2>Placar</h2>
                <div class="scroll">
                  <table>
                    <thead><tr>
                      <th>Jogador</th><th>K</th><th>D</th><th>A</th>
                      <th>HS</th><th>K/D</th><th>Dano</th>
                    </tr></thead>
                    <tbody id="rows"></tbody>
                  </table>
                </div>
                <p class="hint">Clique num jogador para ver as métricas detalhadas.</p>
              </div>

              <div class="card">
                <h2 id="detailTitle">Métricas</h2>
                <div id="detail"></div>
              </div>

              <footer>Análise gerada a partir da demo oficial da partida.</footer>
            </div>

            <script>
            const DATA = __MATCH_DATA__;

            // Rótulos legíveis para as chaves cruas emitidas pelas strategies.
            const LABELS = {
              headshotPercentage:  ["Headshot %",            v => v.toFixed(1) + "%"],
              killsPerRound:       ["Kills / round",          v => v.toFixed(2)],
              deathsPerRound:      ["Mortes / round",         v => v.toFixed(2)],
              kdRatio:             ["K/D",                    v => v.toFixed(2)],
              crosshairPlacementScore: ["Crosshair placement", v => v.toFixed(1) + "%"],
              totalKills:          ["Kills",                  v => v.toFixed(0)],
              totalDeaths:         ["Mortes",                 v => v.toFixed(0)],
              totalHeadshotKills:  ["Kills de HS",            v => v.toFixed(0)],
              flashEfficiency:     ["Eficiência de flash",    v => v.toFixed(1) + "%"],
              teamFlashRate:       ["Flash em aliado",        v => v.toFixed(1) + "%"],
              avgEnemyFlashDuration: ["Cegueira média",       v => v.toFixed(2) + "s"],
              totalFlashesThrown:  ["Flashes lançadas",       v => v.toFixed(0)],
              totalEnemyBlinds:    ["Inimigos cegados",       v => v.toFixed(0)],
              totalTeamBlinds:     ["Aliados cegados",        v => v.toFixed(0)],
              flashesPerRound:     ["Flashes / round",        v => v.toFixed(2)],
              totalUtilityDamage:  ["Dano de utilitária",     v => v.toFixed(0)],
              utilityDamagePerRound: ["Dano util. / round",   v => v.toFixed(1)],
              totalSmokesThrown:   ["Smokes",                 v => v.toFixed(0)],
              smokesPerRound:      ["Smokes / round",         v => v.toFixed(2)],
              totalHEThrown:       ["HEs",                    v => v.toFixed(0)],
              totalMolotovThrown:  ["Molotovs",               v => v.toFixed(0)],

              // Impacto
              adr:                 ["ADR",                    v => v.toFixed(1)],
              totalDamage:         ["Dano total",             v => v.toFixed(0)],
              tradeKills:          ["Trade kills",            v => v.toFixed(0)],
              tradedDeaths:        ["Mortes vingadas",        v => v.toFixed(0)],
              openingDuels:        ["Primeiros duelos",       v => v.toFixed(0)],
              openingDuelsWon:     ["Primeiros duelos ganhos", v => v.toFixed(0)],
              openingDuelWinRate:  ["Taxa 1º duelo",          v => v.toFixed(1) + "%"],
              clutchesWon:         ["Clutches ganhos",        v => v.toFixed(0)],
              clutchesAttempted:   ["Clutches tentados",      v => v.toFixed(0)],
              clutchWinRate:       ["Taxa de clutch",         v => v.toFixed(1) + "%"],
            };

            const fmt = (k, v) => {
              const e = LABELS[k];
              if (!e) return [k, Number.isInteger(v) ? String(v) : v.toFixed(2)];
              return [e[0], e[1](v)];
            };

            const el = id => document.getElementById(id);
            const txt = s => { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; };

            function renderHeader() {
              const ct = DATA.scoreCT ?? 0, tr = DATA.scoreTR ?? 0;
              el("title").textContent = DATA.mapName || "Partida";

              const bits = [];
              bits.push(`<span><b>${Math.max(ct,tr)}-${Math.min(ct,tr)}</b></span>`);
              if (DATA.totalRounds) bits.push(`<span>${DATA.totalRounds} rounds</span>`);
              if (DATA.durationSeconds) bits.push(`<span>${Math.round(DATA.durationSeconds/60)} min</span>`);
              if (DATA.tickRate) bits.push(`<span>${DATA.tickRate} tick</span>`);
              if (DATA.playedAt) {
                const d = new Date(DATA.playedAt);
                if (!isNaN(d)) bits.push(`<span>${d.toLocaleString("pt-BR")}</span>`);
              }
              el("meta").innerHTML = bits.join("");
            }

            function renderRows() {
              const tb = el("rows");
              tb.innerHTML = "";
              (DATA.players || []).forEach((p, i) => {
                const kd = p.deaths > 0 ? (p.kills / p.deaths) : p.kills;
                const tr = document.createElement("tr");
                tr.innerHTML =
                  `<td class="nm">${txt(p.playerName || p.steamId64)}</td>` +
                  `<td>${p.kills}</td><td>${p.deaths}</td><td>${p.assists}</td>` +
                  `<td>${p.headshots}</td><td>${kd.toFixed(2)}</td><td>${p.damage}</td>`;
                tr.onclick = () => select(i);
                tb.appendChild(tr);
              });
            }

            function select(i) {
              document.querySelectorAll("#rows tr").forEach((r, j) =>
                r.classList.toggle("sel", i === j));

              const p = (DATA.players || [])[i];
              if (!p) return;

              el("detailTitle").textContent = "Métricas — " + (p.playerName || p.steamId64);

              let html = "";
              const cats = Object.keys(p.metrics || {});

              if (!cats.length) {
                html = `<p class="hint">Sem métricas calculadas para este jogador.</p>`;
              }

              for (const cat of cats) {
                html += `<div class="cat"><h3>${txt(cat)}</h3><div class="grid">`;
                for (const [k, v] of Object.entries(p.metrics[cat])) {
                  const [label, val] = fmt(k, v);
                  html += `<div class="stat"><div class="k">${txt(label)}</div>` +
                          `<div class="v">${txt(val)}</div></div>`;
                }
                html += `</div>`;

                const tips = (p.insights || {})[cat];
                if (tips && Object.keys(tips).length) {
                  html += `<ul class="tips">`;
                  for (const t of Object.values(tips)) html += `<li>${txt(t)}</li>`;
                  html += `</ul>`;
                }
                html += `</div>`;
              }

              el("detail").innerHTML = html;
            }

            renderHeader();
            renderRows();
            select(0);
            </script>
            </body>
            </html>
            """;
}
