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
                  <p class="hint" style="margin:-.5rem 0 1rem">
                    A cor do número compara com a <b>média desta partida</b>:
                    <span class="up">▲ acima</span> ·
                    <span class="dim">— na média</span> ·
                    <span class="down">▼ abaixo</span>.
                    Contadores sem juízo de valor ficam neutros.
                  </p>
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

            /**
             * Configuração por métrica.
             *
             *   rotulo    — nome legível (as chaves vêm cruas das strategies)
             *   fmt       — formatador do valor
             *   dir       — direção do "melhor": "maior" (padrão), "menor" ou
             *               "neutro". Sem isso, comparar com a média da partida
             *               pintaria de verde quem MAIS morreu por round.
             *   icone     — família visual; ver ICONS em HudComponents
             *   principal — recebe destaque de tamanho e glow
             *   ajuda     — tooltip para métrica cujo cálculo não é óbvio
             *
             * A direção duplica a flag `maiorEhMelhor` do BaselineService para
             * as 10 métricas que ele cobre. É deliberado: a comparação aqui é
             * client-side e as outras ~20 métricas não estão naquela whitelist.
             * Se um dia um limiar mudar lá, este mapa precisa acompanhar.
             */
            const METRICAS = {
              // ─── Aim ───────────────────────────────────────────────
              kdRatio:             {rotulo:"K/D", fmt:v=>v.toFixed(2), icone:"mira", principal:true},
              headshotPercentage:  {rotulo:"Headshot %", fmt:v=>v.toFixed(1)+"%", icone:"mira",
                                    ajuda:"Proporção das suas kills que foram na cabeça. Só existe se você matou alguém."},
              killsPerRound:       {rotulo:"Kills / round", fmt:v=>v.toFixed(2), icone:"mira"},
              deathsPerRound:      {rotulo:"Mortes / round", fmt:v=>v.toFixed(2), icone:"morte", dir:"menor"},
              crosshairPlacementScore: {rotulo:"Crosshair placement", fmt:v=>v.toFixed(1)+"%", icone:"mira",
                                    ajuda:"Percentual de disparos em que a mira já estava a menos de 5° da cabeça do inimigo. Quanto maior, menos ajuste o duelo exige."},
              medianCrosshairErrorDegrees: {rotulo:"Erro de mira (mediana)", fmt:v=>v.toFixed(1)+"°", icone:"mira", dir:"menor",
                                    ajuda:"Ângulo mediano entre a sua mira e a cabeça do inimigo no instante do disparo. Menor é melhor."},
              evaluatedShots:      {rotulo:"Disparos avaliados", fmt:v=>v.toFixed(0), icone:"mira", dir:"neutro",
                                    ajuda:"Quantos disparos tinham um inimigo no cone frontal e puderam entrar no cálculo de mira."},
              totalKills:          {rotulo:"Kills", fmt:v=>v.toFixed(0), icone:"mira"},
              totalDeaths:         {rotulo:"Mortes", fmt:v=>v.toFixed(0), icone:"morte", dir:"menor"},
              totalHeadshotKills:  {rotulo:"Kills de HS", fmt:v=>v.toFixed(0), icone:"mira"},

              // ─── Utility ───────────────────────────────────────────
              flashEfficiency:     {rotulo:"Eficiência de flash", fmt:v=>v.toFixed(1)+"%", icone:"granada", principal:true,
                                    ajuda:"Percentual das suas flashes que cegaram ao menos um inimigo. Cada flash conta no máximo uma vez."},
              teamFlashRate:       {rotulo:"Flash em aliado", fmt:v=>v.toFixed(1)+"%", icone:"granada", dir:"menor"},
              avgEnemyFlashDuration: {rotulo:"Cegueira média", fmt:v=>v.toFixed(2)+"s", icone:"granada"},
              enemyBlindsPerFlash: {rotulo:"Inimigos por flash", fmt:v=>v.toFixed(2), icone:"granada",
                                    ajuda:"Quantos inimigos cada flash cega, em média. Diferente da eficiência, passa de 1 legitimamente."},
              totalFlashesThrown:  {rotulo:"Flashes lançadas", fmt:v=>v.toFixed(0), icone:"granada", dir:"neutro"},
              totalEnemyBlinds:    {rotulo:"Inimigos cegados", fmt:v=>v.toFixed(0), icone:"granada"},
              totalTeamBlinds:     {rotulo:"Aliados cegados", fmt:v=>v.toFixed(0), icone:"granada", dir:"menor"},
              flashesPerRound:     {rotulo:"Flashes / round", fmt:v=>v.toFixed(2), icone:"granada", dir:"neutro"},
              totalUtilityDamage:  {rotulo:"Dano de utilitária", fmt:v=>v.toFixed(0), icone:"dano"},
              utilityDamagePerRound: {rotulo:"Dano util. / round", fmt:v=>v.toFixed(1), icone:"dano"},
              totalSmokesThrown:   {rotulo:"Smokes", fmt:v=>v.toFixed(0), icone:"fumaca", dir:"neutro"},
              smokesPerRound:      {rotulo:"Smokes / round", fmt:v=>v.toFixed(2), icone:"fumaca", dir:"neutro"},
              totalHEThrown:       {rotulo:"HEs", fmt:v=>v.toFixed(0), icone:"granada", dir:"neutro"},
              totalMolotovThrown:  {rotulo:"Molotovs", fmt:v=>v.toFixed(0), icone:"granada", dir:"neutro"},

              // ─── Impacto ───────────────────────────────────────────
              adr:                 {rotulo:"ADR", fmt:v=>v.toFixed(1), icone:"dano", principal:true,
                                    ajuda:"Dano médio por round. Captura a contribuição de quem abre o duelo sem finalizar."},
              totalDamage:         {rotulo:"Dano total", fmt:v=>v.toFixed(0), icone:"dano"},
              tradeKills:          {rotulo:"Trade kills", fmt:v=>v.toFixed(0), icone:"duelo",
                                    ajuda:"Vezes que você matou quem tinha acabado de matar um aliado, em até 5 s."},
              tradedDeaths:        {rotulo:"Mortes vingadas", fmt:v=>v.toFixed(0), icone:"duelo",
                                    ajuda:"Vezes que um aliado matou o seu algoz logo depois. Morrer sendo trocado custa menos ao round."},
              openingDuels:        {rotulo:"Primeiros duelos", fmt:v=>v.toFixed(0), icone:"duelo", dir:"neutro"},
              openingDuelsWon:     {rotulo:"Primeiros duelos ganhos", fmt:v=>v.toFixed(0), icone:"duelo"},
              openingDuelWinRate:  {rotulo:"Taxa 1º duelo", fmt:v=>v.toFixed(1)+"%", icone:"duelo",
                                    ajuda:"Quantos dos primeiros duelos do round você venceu. É o duelo de maior impacto no resultado."},
              clutchesWon:         {rotulo:"Clutches ganhos", fmt:v=>v.toFixed(0), icone:"relogio"},
              clutchesAttempted:   {rotulo:"Clutches tentados", fmt:v=>v.toFixed(0), icone:"relogio", dir:"neutro"},
              clutchWinRate:       {rotulo:"Taxa de clutch", fmt:v=>v.toFixed(1)+"%", icone:"relogio"},
            };

            const cfg = k => METRICAS[k] || {rotulo:k, fmt:v=>Number.isInteger(v)?String(v):v.toFixed(2), dir:"neutro"};

            /**
             * Média de cada métrica entre os jogadores da partida.
             *
             * Escolhemos a média da PARTIDA, e não o baseline da faixa de rank,
             * porque ela existe sempre: vale para os dez jogadores e para todas
             * as ~30 métricas, sem depender das 30 amostras históricas que o
             * BaselineService exige. É uma referência mais fraca, mas presente.
             *
             * Só entram no denominador os jogadores que TÊM a chave — uma
             * métrica ausente (ver a regra de "zero medido vs. ausência") não
             * pode virar zero aqui e puxar a média para baixo.
             */
            function mediasDaPartida(players) {
              const soma = {}, n = {};
              for (const p of players || []) {
                for (const cat of Object.values(p.metrics || {})) {
                  for (const [k, v] of Object.entries(cat)) {
                    if (typeof v !== "number") continue;
                    soma[k] = (soma[k] || 0) + v;
                    n[k] = (n[k] || 0) + 1;
                  }
                }
              }
              const media = {};
              // Com um único jogador não há com quem comparar: sem média, o
              // card sai neutro em vez de fingir um veredito.
              for (const k of Object.keys(soma)) if (n[k] > 1) media[k] = soma[k] / n[k];
              return media;
            }

            /** Largura da faixa considerada "na média", relativa ao próprio valor médio. */
            const ZONA_MORTA = 0.05;

            function statusDaMetrica(chave, valor, media) {
              const dir = cfg(chave).dir || "maior";
              if (dir === "neutro" || media === undefined || !isFinite(media)) return "neutro";

              // Diferença relativa: 5% de 100 de ADR é outra coisa que 5% de
              // 0.9 de K/D, e um limiar absoluto trataria os dois igual.
              const escala = Math.abs(media) || 1;
              const delta = (valor - media) / escala;
              if (Math.abs(delta) <= ZONA_MORTA) return "neutro";

              const acima = delta > 0;
              return (dir === "menor" ? !acima : acima) ? "acima" : "abaixo";
            }

            /** Calculada uma vez: a média não muda ao trocar de jogador selecionado. */
            const MEDIAS = mediasDaPartida(DATA.players);

            const el = id => document.getElementById(id);
            const txt = s => { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; };

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

                // As principais primeiro: elas ocupam duas colunas, e deixá-las
                // na ordem crua abriria buracos na grade.
                const entradas = Object.entries(p.metrics[cat])
                  .sort((a, b) => (cfg(b[0]).principal ? 1 : 0) - (cfg(a[0]).principal ? 1 : 0));

                for (const [k, v] of entradas) {
                  const c = cfg(k);
                  html += MetricCard({
                    label: c.rotulo,
                    valor: c.fmt(v),
                    icone: c.icone,
                    status: statusDaMetrica(k, v, MEDIAS[k]),
                    principal: c.principal,
                    ajuda: c.ajuda,
                  });
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
