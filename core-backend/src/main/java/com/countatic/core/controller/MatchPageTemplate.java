package com.countatic.core.controller;

/**
 * Template HTML da página de detalhes da partida.
 *
 * <p>Fica isolado do controller apenas por legibilidade — é uma string grande
 * e sem lógica de negócio. Os dados entram como JSON embutido em
 * {@code __MATCH_DATA__}.</p>
 *
 * <p>A identidade visual vem de {@link HudTheme}; aqui ficam só os componentes
 * próprios desta tela — o cabeçalho com o badge de rank, o placar e o painel de
 * comparação.</p>
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

    /** CSS exclusivo desta tela. O vocabulário compartilhado está em {@link HudTheme#CSS}. */
    private static final String PAGE_CSS = """
            /* ─── CABEÇALHO ───────────────────────────────────────────── */
            .hdr{display:flex;align-items:center;gap:1.5rem;flex-wrap:wrap;
                 margin-bottom:1.75rem}
            .hdr-main{flex:1 1 320px;min-width:0}

            .brand{font-family:var(--f-mono);font-size:.72rem;letter-spacing:.22em;
                   text-transform:uppercase;color:var(--neon);margin-bottom:.55rem}
            .brand span{color:var(--muted)}

            .meta{display:flex;flex-wrap:wrap;gap:.4rem .55rem;margin-top:.85rem}
            .meta .tag{font-family:var(--f-mono);font-size:.74rem;
                       letter-spacing:.06em;color:var(--muted);
                       background:var(--tint);border:1px solid var(--line);
                       padding:.2rem .55rem}
            .meta .tag b{color:var(--text);font-weight:400}

            /* ─── PLACAR ──────────────────────────────────────────────── */
            .score{display:flex;align-items:center;gap:.9rem;margin-top:1rem}
            .score .side{text-align:center}
            .score .num{font-family:var(--f-display);font-weight:900;
                        font-size:2.1rem;line-height:1;letter-spacing:.02em}
            /* Cada lado brilha na própria cor: o glow herda currentColor em vez
               de ser roxo fixo, senão o azul do CT ficaria com halo violeta. */
            .score .ct .num{color:var(--ct);text-shadow:0 0 16px rgba(111,159,216,.5)}
            .score .tr .num{color:var(--tr);text-shadow:0 0 16px rgba(233,180,76,.5)}
            .score .who{font-size:.64rem;font-weight:700;letter-spacing:.16em;
                        text-transform:uppercase;margin-top:.3rem}
            .score .ct .who{color:var(--ct)}
            .score .tr .who{color:var(--tr)}
            .score .sep{width:1px;height:34px;background:var(--line)}

            /* ─── TABELA DO PLACAR ────────────────────────────────────── */
            /* Tabela larga rola dentro do próprio container: a página nunca
               rola na horizontal. */
            .scroll{overflow-x:auto;-webkit-overflow-scrolling:touch}
            table{width:100%;border-collapse:collapse;min-width:620px}

            thead th{
              font-family:var(--f-body);font-size:.68rem;font-weight:600;
              letter-spacing:.14em;text-transform:uppercase;color:var(--muted);
              text-align:right;padding:.5rem .6rem;
              border-bottom:1px solid var(--line);white-space:nowrap;
            }
            thead th:first-child{text-align:left}

            tbody td{padding:.6rem;text-align:right;white-space:nowrap;
                     font-family:var(--f-mono);font-size:.92rem;
                     border-bottom:1px solid rgba(176,38,255,.12)}
            tbody td:first-child{text-align:left;white-space:normal;
                                 font-family:var(--f-body);font-weight:600;
                                 font-size:.98rem}

            /* A barra de status fica no primeiro td via inset shadow: com
               border-collapse, uma border-left do tr não é renderizada. */
            tbody tr{--st:var(--neon-dim);cursor:pointer;
                     transition:background .15s}
            tbody tr.is-up{--st:var(--good)}
            tbody tr.is-down{--st:var(--bad)}
            tbody td:first-child{box-shadow:inset 2px 0 0 var(--st)}
            tbody tr:hover{background:rgba(176,38,255,.07)}
            tbody tr[aria-current="true"]{background:rgba(176,38,255,.13)}
            tbody tr[aria-current="true"] td{color:var(--text)}
            tbody tr:last-child td{border-bottom:none}

            .hint{color:var(--muted);font-size:.82rem;margin:.9rem 0 0}

            /* ─── CATEGORIA E COMPARAÇÃO ──────────────────────────────── */
            .cat{margin-bottom:1.5rem}
            .cat > h3{margin-bottom:.7rem}

            .cmp{margin-bottom:.85rem}
            .cmp .row{display:flex;justify-content:space-between;align-items:baseline;
                      gap:.7rem;margin-bottom:.3rem;font-size:.88rem}
            .cmp .lbl{font-weight:600;letter-spacing:.04em}
            .cmp .val{font-family:var(--f-mono);font-size:.84rem}
            .cmp .val b{font-size:.95rem;color:var(--text)}

            .tier{display:inline-block;font-family:var(--f-mono);font-size:.72rem;
                  letter-spacing:.08em;color:var(--neon);
                  background:var(--tint);border:1px solid var(--line);
                  padding:.15rem .5rem}

            footer{position:relative;z-index:2;text-align:center;
                   color:var(--muted);font-size:.76rem;letter-spacing:.08em;
                   padding-bottom:2.5rem}
            """;

    private static final String HTML = ("""
            <!doctype html>
            <html lang="pt-BR">
            <head>
            __META__
              <title>Relatório da Partida — CounTatic</title>
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
                  <div class="brand">CounTatic <span>// Match Report</span></div>
                  <h1 id="title">Relatório da Partida</h1>
                  <div class="score" id="score"></div>
                  <div class="meta" id="meta"></div>
                </div>
                <div id="rank"></div>
              </header>

              <section class="panel cut">
                <div class="cut-in">
                  <div class="panel-head"><h2>Placar</h2></div>
                  <div class="scroll">
                    <table>
                      <thead><tr>
                        <th scope="col">Jogador</th>
                        <th scope="col">K</th>
                        <th scope="col">D</th>
                        <th scope="col">A</th>
                        <th scope="col">HS</th>
                        <th scope="col">K/D</th>
                        <th scope="col">Dano</th>
                      </tr></thead>
                      <tbody id="rows"></tbody>
                    </table>
                  </div>
                  <p class="hint">Selecione um jogador para ver as métricas detalhadas.</p>
                </div>
              </section>

              <section class="panel cut">
                <div class="cut-in">
                  <div class="panel-head"><h2 id="detailTitle">Métricas</h2></div>
                  <div id="detail" aria-live="polite"></div>
                </div>
              </section>

              <section class="panel cut">
                <div class="cut-in">
                  <div class="panel-head"><h2 id="coachTitle">O que treinar</h2></div>
                  <div id="coach" aria-live="polite"></div>
                </div>
              </section>

            </div>
            <footer>Análise gerada a partir da demo oficial da partida</footer>

            <script>
            __COMPONENTS_JS__

            const DATA = __MATCH_DATA__;

            // Rótulos legíveis para as chaves cruas emitidas pelas strategies.
            const LABELS = {
              headshotPercentage:  ["Headshot %",            v => v.toFixed(1) + "%"],
              killsPerRound:       ["Kills / round",          v => v.toFixed(2)],
              deathsPerRound:      ["Mortes / round",         v => v.toFixed(2)],
              kdRatio:             ["K/D",                    v => v.toFixed(2)],
              crosshairPlacementScore: ["Crosshair placement", v => v.toFixed(1) + "%"],
              medianCrosshairErrorDegrees: ["Erro de mira (mediana)", v => v.toFixed(1) + "°"],
              evaluatedShots:      ["Disparos avaliados",     v => v.toFixed(0)],
              totalKills:          ["Kills",                  v => v.toFixed(0)],
              totalDeaths:         ["Mortes",                 v => v.toFixed(0)],
              totalHeadshotKills:  ["Kills de HS",            v => v.toFixed(0)],
              flashEfficiency:     ["Eficiência de flash",    v => v.toFixed(1) + "%"],
              teamFlashRate:       ["Flash em aliado",        v => v.toFixed(1) + "%"],
              avgEnemyFlashDuration: ["Cegueira média",       v => v.toFixed(2) + "s"],
              enemyBlindsPerFlash: ["Inimigos por flash",     v => v.toFixed(2)],
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

            /** Chip de métrica: duas camadas, porque o chanfro corta a borda junto com o fundo. */
            const chip = (label, value) =>
              `<div class="chip cut"><div class="cut-in">` +
              `<div class="k">${txt(label)}</div><div class="v">${txt(value)}</div>` +
              `</div></div>`;

            /**
             * Comparação com jogadores da mesma faixa de CS Rating.
             *
             * Quando a amostra é pequena, mostra o aviso em vez de um percentil:
             * um número calculado sobre poucos dados parece preciso sem ser.
             */
            function renderBaseline(b) {
              if (!b) return "";

              if (!b.amostraSuficiente) {
                return `<div class="cat"><div class="note">${txt(b.aviso || "Sem comparação disponível.")}</div></div>`;
              }

              const metricas = Object.values(b.metricas || {});
              if (!metricas.length) return "";

              let html = `<div class="cat"><h3>Comparado com a faixa</h3>` +
                         `<p class="hint" style="margin:-.35rem 0 .9rem">` +
                         `<span class="tier">${txt(b.faixaLabel)}</span> ` +
                         `<span class="dim">· base de ${b.amostraTotal} desempenhos ` +
                         `já analisados nesta faixa</span></p>`;

              for (const c of metricas) {
                // Verde no topo, vermelho na base, roxo no meio. O glifo ▲/▼
                // vai junto da cor: verde e vermelho é o par que o daltonismo
                // mais comum não separa, então a forma é quem garante a
                // leitura. O "top X%" ao lado diz a mesma coisa em texto.
                const p = Math.max(0, Math.min(100, c.percentil));
                const alto = p >= 60, baixo = p < 40;
                const cls  = alto ? "is-up" : (baixo ? "is-down" : "");
                const tone = alto ? "up"    : (baixo ? "down"    : "");
                const sig  = alto ? "▲"     : (baixo ? "▼"       : "—");

                html += `<div class="cmp">
                    <div class="row">
                      <span class="lbl">${txt(c.rotulo)}</span>
                      <span class="val"><b class="${tone}">` +
                        `<span class="sig" aria-hidden="true">${sig}</span>${txt(c.valor)}</b>
                        <span class="dim"> · média ${txt(c.media)} · top ${(100 - p).toFixed(0)}%</span>
                      </span>
                    </div>
                    <div class="bar"><i class="${cls}" style="width:${p}%"></i></div>
                  </div>`;
              }

              return html + `</div>`;
            }

            function renderHeader() {
              el("title").textContent = DATA.mapName || "Partida";

              // Placar por lado, com as cores dos times no CS2: CT azul, TR
              // amarelo. "maior-menor" não diria de quem é o número, e o DTO
              // não informa de que lado o dono da partida jogou.
              const ct = DATA.scoreCT, tr = DATA.scoreTR;
              if (ct != null && tr != null) {
                el("score").innerHTML =
                  `<div class="side ct"><div class="num">${ct}</div><div class="who">CT</div></div>` +
                  `<div class="sep"></div>` +
                  `<div class="side tr"><div class="num">${tr}</div><div class="who">TR</div></div>`;
              }

              const tag = (label, value) =>
                `<span class="tag">${txt(label)} <b>${txt(value)}</b></span>`;

              const bits = [];
              if (DATA.totalRounds) bits.push(tag("Rounds", DATA.totalRounds));
              if (DATA.durationSeconds) bits.push(tag("Duração", Math.round(DATA.durationSeconds / 60) + " min"));
              if (DATA.tickRate) bits.push(tag("Tick", DATA.tickRate));
              if (DATA.rankTierLabel) bits.push(tag("Faixa", DATA.rankTierLabel));
              if (DATA.playedAt) {
                const d = new Date(DATA.playedAt);
                if (!isNaN(d)) bits.push(tag("Jogada em", d.toLocaleString("pt-BR")));
              }
              el("meta").innerHTML = bits.join("");

              // O badge só existe quando há CS Rating: um anel pulsante em volta
              // de "—" chamaria atenção para a ausência do dado.
              if (DATA.csRating != null) {
                // A cor e o nome vêm da faixa do Premier. O nome legível sai do
                // próprio enum ("AZUL_CLARO" -> "Azul claro"), o que evita
                // fatiar o rankTierLabel — que existe para exibir a faixa
                // numérica e pode mudar de formato sem aviso.
                const tier = DATA.rankTier || "";
                const cls  = tier ? " t-" + tier.toLowerCase().replace(/_/g, "-") : "";
                const nome = tier
                  ? tier.charAt(0) + tier.slice(1).toLowerCase().replace(/_/g, " ")
                  : "Rating";

                el("rank").innerHTML =
                  `<div class="badge${cls}" title="CS Rating de quem cadastrou a partida">` +
                  `<div><div class="n">${txt(DATA.csRating)}</div>` +
                  `<div class="t">${txt(nome)}</div></div></div>`;
              }
            }

            function renderRows() {
              const tb = el("rows");
              tb.innerHTML = "";

              (DATA.players || []).forEach((p, i) => {
                const kd = p.deaths > 0 ? (p.kills / p.deaths) : p.kills;
                const tr = document.createElement("tr");

                // A barra lateral e o K/D marcam o status da linha: a partir de
                // 1.0 é positivo. É o único corte com significado disponível
                // aqui — o DTO não diz quem venceu a partida.
                const positivo = kd >= 1;
                tr.className = positivo ? "is-up" : "is-down";
                tr.tabIndex = 0;
                tr.innerHTML =
                  `<td>${txt(p.playerName || p.steamId64)}</td>` +
                  `<td>${p.kills}</td><td>${p.deaths}</td><td>${p.assists}</td>` +
                  `<td>${p.headshots}</td>` +
                  `<td class="${positivo ? "up" : "down"}">` +
                    `<span class="sig" aria-hidden="true">${positivo ? "▲" : "▼"}</span>` +
                    `${kd.toFixed(2)}</td>` +
                  `<td>${p.damage}</td>`;

                tr.addEventListener("click", () => select(i));
                // A linha é o controle de seleção da página inteira; sem isto
                // ela seria inalcançável por teclado.
                tr.addEventListener("keydown", ev => {
                  if (ev.key === "Enter" || ev.key === " ") {
                    ev.preventDefault();
                    select(i);
                  }
                });

                tb.appendChild(tr);
              });
            }

            function select(i) {
              document.querySelectorAll("#rows tr").forEach((r, j) =>
                r.setAttribute("aria-current", String(i === j)));

              const p = (DATA.players || [])[i];
              if (!p) return;

              el("detailTitle").textContent = "Métricas — " + (p.playerName || p.steamId64);

              let html = renderBaseline(p.baseline);
              const cats = Object.keys(p.metrics || {});

              if (!cats.length) {
                html = `<p class="hint">Sem métricas calculadas para este jogador.</p>`;
              }

              for (const cat of cats) {
                html += `<div class="cat"><h3>${txt(cat)}</h3><div class="chips">`;
                for (const [k, v] of Object.entries(p.metrics[cat])) {
                  const [label, val] = fmt(k, v);
                  html += chip(label, val);
                }
                html += `</div></div>`;
              }

              el("detail").innerHTML = html;

              // As dicas saíram de baixo de cada categoria e passaram a um
              // painel próprio, ordenado por gravidade. Espalhadas, um elogio e
              // um alerta tinham o mesmo peso e o jogador precisava ler tudo
              // para achar o que fazer.
              el("coachTitle").textContent =
                "O que treinar — " + (p.playerName || p.steamId64);
              el("coach").innerHTML = CoachPanel(achatarInsights(p.insights));
            }

            renderHeader();
            renderRows();
            select(0);
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
