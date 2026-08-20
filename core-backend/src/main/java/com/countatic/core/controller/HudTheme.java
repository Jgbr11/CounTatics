package com.countatic.core.controller;

/**
 * Identidade visual do CounTatic: HUD tática cyberpunk, roxo neon.
 *
 * <p><b>Por que existe.</b> As telas do sistema são HTML autocontido gerado em
 * Java (sem build de frontend, sem CORS, um container só). Sem um lugar comum,
 * cada tela nasceria com a própria cópia do CSS e as duas divergiriam no
 * primeiro ajuste. Aqui ficam os tokens e os componentes; cada página
 * acrescenta só o que é dela.</p>
 *
 * <p><b>Vocabulário.</b> O contraste é entre <i>escuro</i> e <i>neon</i>, não
 * entre camadas de profundidade suave: bordas de 1px, nunca sombras difusas.
 * Cantos cortados por {@code clip-path} no lugar de {@code border-radius},
 * porque canto arredondado lê como "app comum" e canto chanfrado lê como
 * "HUD".</p>
 *
 * <p><b>Semântica de cor.</b> Ciano é positivo, magenta é negativo, roxo é
 * marca e neutro de destaque. O glow fica reservado ao que é realmente
 * principal — número grande, badge de rank, borda ativa. Glow em tudo é glow em
 * nada.</p>
 */
final class HudTheme {

    private HudTheme() {
    }

    /**
     * Fontes do Google Fonts.
     *
     * <p><b>Compromisso assumido.</b> São três famílias que não existem no
     * sistema, então vêm de fora — o que significa uma dependência de rede numa
     * página servida em {@code 127.0.0.1}. O {@code display=swap} faz o texto
     * aparecer imediatamente na fonte de fallback em vez de ficar invisível
     * esperando o download, e a pilha de fallback de cada família está definida
     * no CSS. Sem internet a página fica legível e coerente, só perde o
     * caráter. Embutir as fontes em base64 no Java resolveria a dependência ao
     * custo de megabytes de string no código-fonte — não compensa aqui.</p>
     */
    static final String FONTS = """
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link rel="stylesheet" href="https://fonts.googleapis.com/css2?\
            family=Orbitron:wght@700;900&\
            family=Rajdhani:wght@400;500;600;700&\
            family=Share+Tech+Mono&display=swap">
            """;

    /** Metas comuns a toda tela: viewport, esquema de cor e cor da barra do navegador. */
    static final String META = """
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="color-scheme" content="dark">
            <meta name="theme-color" content="#0A0714">
            """;

    /**
     * Base compartilhada: tokens, reset, fundo, tipografia e os componentes do
     * vocabulário (chip, badge, barra, botão, nota).
     *
     * <p>Vai dentro de {@code <style>} — sem as tags, para a página poder
     * concatenar o CSS dela na sequência.</p>
     */
    private static final String BASE = """
            /* ───────────────────────────────────────────────────────────
               TOKENS
               Uma única fonte de verdade para cor, fonte e geometria.
               ─────────────────────────────────────────────────────────── */
            :root{
              color-scheme:dark;

              --bg:#0A0714;
              --panel-1:#120B24;
              --panel-2:#170F2E;

              --neon:#B026FF;
              --neon-dim:#6B2FA0;

              /* Times: as cores do próprio CS2. Um placar de CS em que o CT não
                 é azul e o TR não é amarelo obriga o jogador a ler o rótulo
                 para saber de quem é o número — a cor tem que dizer isso
                 sozinha, como diz dentro do jogo. */
              --ct:#6F9FD8;
              --tr:#E9B44C;

              /* Estados: verde positivo, vermelho negativo.
                 Verde e vermelho é o par MENOS distinguível para daltonismo
                 do tipo protan/deutan, que é o mais comum. Por isso nenhum
                 indicador desta interface depende só da cor: todo lugar que
                 usa --good/--bad carrega junto um sinal de forma (▲ ▼) e o
                 número continua legível por si. A cor acelera a leitura de
                 quem enxerga a diferença; ela nunca é a única portadora. */
              --good:#2BE08A;
              --bad:#FF4D6A;

              /* Ciano fica reservado ao anel de foco — afordância de
                 interface, não estado. Misturá-lo com os estados faria "está
                 focado" parecer "está bom". */
              --focus:#00F0FF;

              --text:#E8E3F5;
              --muted:#8B7FA8;

              --line:rgba(176,38,255,.25);
              --tint:rgba(176,38,255,.06);

              /* Tamanho do chanfro. Trocar aqui muda a linguagem da tela toda. */
              --cut:10px;

              --f-display:'Orbitron','Eurostile','Arial Narrow',system-ui,sans-serif;
              --f-body:'Rajdhani','Segoe UI',system-ui,-apple-system,sans-serif;
              --f-mono:'Share Tech Mono','JetBrains Mono',ui-monospace,Consolas,monospace;
            }

            *,*::before,*::after{box-sizing:border-box}

            body{
              margin:0;
              min-height:100vh;
background:var(--bg);
              color:var(--text);
              font:400 16px/1.6 var(--f-body);
              -webkit-font-smoothing:antialiased;
            }

            /* ───────────────────────────────────────────────────────────
               FUNDO
               Duas camadas fixas, ambas inertes ao ponteiro: a grade dá a
               sensação de instrumento, as scanlines dão a de tela CRT.
               Ficam em position:fixed para não rolarem com o conteúdo — uma
               grade que rola vira textura e perde o efeito de "painel".
               ─────────────────────────────────────────────────────────── */
            body::before{
              content:"";
              position:fixed;
              inset:0;
              z-index:0;
              pointer-events:none;
              background-image:
                linear-gradient(to right, rgba(176,38,255,.05) 1px, transparent 1px),
                linear-gradient(to bottom, rgba(176,38,255,.05) 1px, transparent 1px);
              background-size:40px 40px;
            }

            body::after{
              content:"";
              position:fixed;
              inset:0;
              z-index:1;
              pointer-events:none;
              opacity:.35;
              background:repeating-linear-gradient(
                to bottom,
                rgba(0,0,0,.18) 0 1px,
                transparent 1px 3px
              );
              animation:hud-flicker 7s steps(1) infinite;
            }

            /* O flicker é quase imperceptível de propósito: a HUD deve parecer
               viva, não com defeito. Passa 96% do ciclo parada. */
            @keyframes hud-flicker{
              0%,96%,100%{opacity:.35}
              97%{opacity:.5}
              98%{opacity:.28}
              99%{opacity:.44}
            }

            /* ───────────────────────────────────────────────────────────
               NAVEGAÇÃO
               Fixa no topo: numa página longa de estatística, a saída não
               pode exigir rolar até o começo. Fundo opaco porque ela passa
               por cima do conteúdo.
               ─────────────────────────────────────────────────────────── */
            .nav{
              position:sticky;top:0;z-index:5;
              background:rgba(10,7,20,.92);
              backdrop-filter:blur(6px);
              border-bottom:1px solid var(--line);
            }
            .nav-in{max-width:1140px;margin:0 auto;padding:.7rem 1rem;
                    display:flex;align-items:center;gap:1.25rem}

            .nav-marca{font-family:var(--f-mono);font-size:.78rem;letter-spacing:.22em;
                       text-transform:uppercase;color:var(--neon);text-decoration:none}
            a.nav-marca:hover{text-shadow:0 0 12px rgba(176,38,255,.55)}

            .nav-links{display:flex;gap:.25rem;margin-left:auto;flex-wrap:wrap}
            .nav-links a{
              font-family:var(--f-body);font-size:.72rem;font-weight:600;
              letter-spacing:.12em;text-transform:uppercase;
              color:var(--muted);text-decoration:none;
              padding:.4rem .7rem;
              border:1px solid transparent;
            }
            .nav-links a:hover{color:var(--text);border-color:var(--line)}
            /* O estado ativo se apoia no aria-current, não numa classe: assim
               o visual e o leitor de tela não podem divergir. */
            /* Atalho de volta: cor da marca e sem margem à esquerda, para
               ler como "sair daqui" e não como mais um destino da lista. */
            .nav-links a.nav-volta{color:var(--neon);border-color:var(--line);
                                   margin-right:.5rem}
            .nav-links a.nav-volta:hover{background:var(--tint)}

            .nav-links a[aria-current="page"]{
              color:var(--neon);border-color:var(--line);background:var(--tint)}

            /* Todo conteúdo sobe acima das duas camadas de fundo. */
            .wrap{position:relative;z-index:2;max-width:1140px;margin:0 auto;
                  padding:2rem 1rem 4rem}

            /* ───────────────────────────────────────────────────────────
               TIPOGRAFIA
               ─────────────────────────────────────────────────────────── */
            h1,h2,h3{font-family:var(--f-display);font-weight:700;margin:0}
            h1{font-size:clamp(1.6rem,4vw,2.3rem);font-weight:900;
               letter-spacing:.06em;text-transform:uppercase;line-height:1.15}
            h2{font-size:.82rem;letter-spacing:.18em;text-transform:uppercase;
               color:var(--muted);font-weight:700}
            h3{font-size:.9rem;letter-spacing:.12em;text-transform:uppercase;
               color:var(--neon);font-weight:700}

            .label{font-family:var(--f-body);font-size:.72rem;font-weight:600;
                   letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}
            .mono{font-family:var(--f-mono);font-variant-numeric:tabular-nums}

            /* Tags de metadado do cabeçalho. Usadas pela página da partida e
               pelo painel do jogador — por isso vivem aqui e não numa delas. */
            .meta{display:flex;flex-wrap:wrap;gap:.4rem .55rem;margin-top:.85rem}
            .meta .tag{font-family:var(--f-mono);font-size:.74rem;
                       letter-spacing:.06em;color:var(--muted);
                       background:var(--tint);border:1px solid var(--line);
                       padding:.2rem .55rem}
            .meta .tag b{color:var(--text);font-weight:400}

            /* ───────────────────────────────────────────────────────────
               GEOMETRIA — canto cortado
               O clip-path corta a borda junto com o fundo, então um único
               elemento com `border` perderia a linha justamente na diagonal.
               A solução é de duas camadas: a externa É a borda (1px de
               padding pintado com a cor da linha) e a interna é o
               preenchimento. As duas usam o mesmo recorte.
               ─────────────────────────────────────────────────────────── */
            .cut,.cut-in{
              clip-path:polygon(
                var(--cut) 0,
                100% 0,
                100% calc(100% - var(--cut)),
                calc(100% - var(--cut)) 100%,
                0 100%,
                0 var(--cut)
              );
            }

            /* ───────────────────────────────────────────────────────────
               PAINEL
               ─────────────────────────────────────────────────────────── */
            .panel{
              background:var(--line);
              padding:1px;
              margin-bottom:1.25rem;
            }
            .panel > .cut-in{
              background:linear-gradient(160deg,var(--panel-1),var(--panel-2));
              padding:1.25rem 1.35rem;
            }
            .panel-head{display:flex;align-items:center;gap:.6rem;margin-bottom:1.1rem}
            /* O filete entra ANTES de qualquer ação, empurrando-a para a
               direita — daí o ::after ser irmão e não o último elemento. */
            .panel-head::after{content:"";flex:1;height:1px;background:var(--line);order:1}

            /* ───────────────────────────────────────────────────────────
               METRIC CARD
               O fundo é quase transparente de propósito. A versão anterior
               preenchia cada card com roxo, e vinte cards preenchidos lado a
               lado viravam uma parede: o olho não achava onde pousar. Aqui o
               peso visual está no NÚMERO, não na caixa — a caixa é só a linha
               de 1px que delimita.
               ─────────────────────────────────────────────────────────── */
            .chips{display:grid;gap:.7rem;
                   grid-template-columns:repeat(auto-fill,minmax(178px,1fr))}

            .chip{--st:var(--text);--st-glow:rgba(232,227,245,.35);
                  background:var(--line);padding:1px}
            .chip > .cut-in{background:rgba(255,255,255,.02);padding:.75rem .85rem}

            /* O rótulo é secundário ao número, mas não pode ser ilegível:
               a .66rem em maiúsculas com espaçamento, o roxo apagado ficava
               no limite do contraste mínimo para texto pequeno. Um pouco
               maior, mais pesado e mais claro resolve sem competir com o
               valor, que segue sendo o elemento de peso do card. */
            .chip .k{display:flex;align-items:center;gap:.4rem;
                     font-size:.7rem;font-weight:700;letter-spacing:.12em;
                     text-transform:uppercase;color:#A99CC6;
                     margin-bottom:.45rem;line-height:1.3}
            .chip .k .ico{flex:0 0 auto;opacity:.75}

            /* A cor do valor É o status. O glow acompanha a mesma variável,
               senão um número vermelho ganharia halo roxo. */
            .chip .v{font-family:var(--f-display);font-weight:700;font-size:1.45rem;
                     letter-spacing:.02em;color:var(--st)}
            .chip.is-acima  {--st:var(--good);--st-glow:rgba(43,224,138,.45)}
            .chip.is-abaixo {--st:var(--bad); --st-glow:rgba(255,77,106,.45)}

            /* Recorde pessoal: apenas o CONTORNO dourado, com um brilho leve.
               Tingir o bloco inteiro competia com o número, que é o dado — a
               moldura destaca sem tomar a atenção.

               O fundo do .chip É a borda de 1px (técnica de duas camadas),
               então é ele que recebe o dourado; a camada interna fica como
               nos demais cards.

               O brilho usa drop-shadow, e não box-shadow: o card é recortado
               por clip-path, e clip-path corta a box-shadow junto — o brilho
               simplesmente não apareceria. O filter é aplicado DEPOIS do
               recorte, então a luz acompanha o contorno chanfrado. */
            .chip.is-recorde{background:#FFC53D;
                             filter:drop-shadow(0 0 8px rgba(255,197,61,.45))}

            /* A camada interna precisa ser OPACA aqui.
               Nos demais cards ela é rgba(255,255,255,.02) — quase
               transparente — e deixa a cor de trás atravessar. Com o roxo
               translúcido da borda isso passa despercebido; com o dourado
               sólido, o card inteiro fica amarelo em vez de só o contorno.
               A cor sólida abaixo é a média do gradiente do painel, então o
               card continua idêntico aos outros por dentro. */
            .chip.is-recorde > .cut-in{
              background:linear-gradient(rgba(255,255,255,.02),rgba(255,255,255,.02)),#150E2A;
            }

            .chip .pr{margin-left:.4rem;font-size:.75em;cursor:help;vertical-align:middle}

            /* ─── Hierarquia ───────────────────────────────────────────
               Nem toda métrica pesa igual. K/D e ADR decidem a leitura da
               partida; "disparos avaliados" é diagnóstico. Dar o mesmo
               tamanho aos dois obriga o jogador a filtrar no olho. O card
               principal ocupa duas colunas, cresce a tipografia e é o único
               que recebe glow — que é o que sobrou de destaque depois de o
               fundo virar transparente. */
            .chip.is-principal{grid-column:span 2}
            .chip.is-principal > .cut-in{padding:.95rem 1.05rem}
            .chip.is-principal .v{font-size:2.1rem;font-weight:900;
                                  text-shadow:0 0 18px var(--st-glow)}
            .chip.is-principal .k{font-size:.75rem}

            /* Numa coluna só, "span 2" estouraria a grade. */
            @media (max-width:420px){
              .chip.is-principal{grid-column:span 1}
              .chip.is-principal .v{font-size:1.7rem}
            }

            /* ─── Tooltip ──────────────────────────────────────────────
               Métrica com explicação ganha uma marca discreta e usa o
               title nativo: sem JS de posicionamento, e funciona por
               teclado e leitor de tela sem trabalho extra. */
            .chip .k[title]{cursor:help}
            .chip .k[title]::after{content:"?";margin-left:auto;
                                   font-size:.62rem;opacity:.5;
                                   border:1px solid currentColor;border-radius:50%;
                                   width:1.1em;height:1.1em;line-height:1;
                                   display:inline-flex;align-items:center;
                                   justify-content:center;flex:0 0 auto}

            /* ───────────────────────────────────────────────────────────
               BADGE DE RANK
               Circular, anel neon, pulsação lenta. É a única forma redonda
               da interface — por isso chama atenção sem precisar de tamanho.

               A cor NÃO é a da marca: é a da faixa do Premier. Um jogador
               Azul que visse um anel roxo teria de ler o número para saber
               onde está, e o badge existe justamente para dispensar isso.
               As sete faixas abaixo são as mesmas do enum RankTier.
               O rgba de cada uma vem escrito à mão em vez de derivado por
               color-mix, para não depender de suporte do navegador num
               elemento que é a identidade da tela.
               ─────────────────────────────────────────────────────────── */
            .badge{
              --tier:var(--neon);
              --tier-glow:rgba(176,38,255,.55);
              --tier-soft:rgba(176,38,255,.18);

              display:grid;
              place-items:center;
              width:96px;height:96px;
              flex:0 0 auto;
              border-radius:50%;
              background:radial-gradient(circle at 50% 45%,
                         var(--tier-soft), transparent 72%);
              border:2px solid var(--tier);
              animation:hud-pulse 2.6s ease-in-out infinite;
              text-align:center;
            }
            .badge .n{font-family:var(--f-display);font-weight:900;font-size:1.5rem;
                      letter-spacing:.01em;line-height:1;color:var(--tier);
                      text-shadow:0 0 14px var(--tier-glow)}
            .badge .t{font-family:var(--f-body);font-size:.62rem;font-weight:700;
                      letter-spacing:.14em;text-transform:uppercase;
                      color:var(--tier);margin-top:.3rem;opacity:.9}

            /* ───────────────────────────────────────────────────────────
               CORES DAS FAIXAS
               Genéricas de propósito: qualquer elemento que exiba a faixa
               recebe a classe e passa a falar a mesma cor. Antes isso vivia
               preso ao badge, e o rótulo "Azul" aparecia roxo em todo o resto
               da interface — o oposto do que a faixa comunica.

               Vêm DEPOIS do .badge para vencer o padrão dele por ordem de
               declaração (mesma especificidade).
               ─────────────────────────────────────────────────────────── */
            .t-cinza      {--tier:#B6BECC;--tier-glow:rgba(182,190,204,.55);--tier-soft:rgba(182,190,204,.18)}
            .t-azul-claro {--tier:#6FD3F2;--tier-glow:rgba(111,211,242,.55);--tier-soft:rgba(111,211,242,.18)}
            .t-azul       {--tier:#5B8DEF;--tier-glow:rgba(91,141,239,.60); --tier-soft:rgba(91,141,239,.20)}
            .t-roxo       {--tier:#B026FF;--tier-glow:rgba(176,38,255,.55); --tier-soft:rgba(176,38,255,.18)}
            .t-rosa       {--tier:#FF57C1;--tier-glow:rgba(255,87,193,.55); --tier-soft:rgba(255,87,193,.18)}
            .t-vermelho   {--tier:#FF4D4D;--tier-glow:rgba(255,77,77,.55);  --tier-soft:rgba(255,77,77,.18)}
            .t-ouro       {--tier:#FFC53D;--tier-glow:rgba(255,197,61,.60); --tier-soft:rgba(255,197,61,.20)}

            /* Chip com o nome da faixa. O fallback para --neon cobre o caso de
               não sabermos a faixa: melhor a cor da marca que nenhuma. */
            .tier{display:inline-block;font-family:var(--f-mono);font-size:.72rem;
                  letter-spacing:.08em;
                  color:var(--tier,var(--neon));
                  background:var(--tier-soft,var(--tint));
                  border:1px solid var(--tier,var(--line));
                  padding:.15rem .5rem}

            /* Tag de metadado que carrega a faixa: só o valor ganha a cor, o
               rótulo "Faixa" continua apagado como as demais tags. */
            .meta .tag.is-tier b{color:var(--tier,var(--text))}
            .meta .tag.is-tier{border-color:var(--tier,var(--line))}

            /* O pulso usa a cor da faixa, não a da marca. */
            @keyframes hud-pulse{
              0%,100%{box-shadow:0 0 10px var(--tier-soft), inset 0 0 10px var(--tier-soft)}
              50%    {box-shadow:0 0 24px var(--tier-glow), inset 0 0 14px var(--tier-soft)}
            }

            /* ───────────────────────────────────────────────────────────
               BARRA DE PROGRESSO
               Trilho escuro translúcido; preenchimento em gradiente com um
               glow leve. A cor do preenchimento é decidida por quem usa,
               porque aqui ela carrega significado (ver .is-up / .is-down).
               ─────────────────────────────────────────────────────────── */
            .bar{position:relative;height:6px;background:rgba(0,0,0,.45);
                 border:1px solid var(--line);overflow:hidden}
            .bar > i{position:absolute;inset:0 auto 0 0;display:block;
                     background:linear-gradient(90deg,var(--neon-dim),var(--neon));
                     box-shadow:0 0 10px rgba(176,38,255,.5)}
            .bar > i.is-up{background:linear-gradient(90deg,#147852,var(--good));
                           box-shadow:0 0 10px rgba(43,224,138,.45)}
            .bar > i.is-down{background:linear-gradient(90deg,#8f2036,var(--bad));
                             box-shadow:0 0 10px rgba(255,77,106,.45)}

            /* ───────────────────────────────────────────────────────────
               ITEM DE LISTA
               Barra vertical fina à esquerda indicando o status do item.
               ─────────────────────────────────────────────────────────── */
            .items{list-style:none;padding:0;margin:0}
            .items li{
              position:relative;
              background:var(--tint);
              border:1px solid var(--line);
              border-left:2px solid var(--neon);
              padding:.65rem .85rem;
              margin-bottom:.5rem;
              font-size:.95rem;
            }
            .items li.is-up{border-left-color:var(--good)}
            .items li.is-down{border-left-color:var(--bad)}

            .note{background:var(--tint);border:1px solid var(--line);
                  border-left:2px solid var(--neon-dim);
                  padding:.7rem .85rem;font-size:.92rem;color:var(--muted)}

            /* ───────────────────────────────────────────────────────────
               TREND CHART
               Sem grade: a única horizontal é a média da própria série.
               O SVG estica na largura e trava a altura, entao o grafico
               acompanha a coluna sem recalcular nada em JS.
               ─────────────────────────────────────────────────────────── */
            .trend{margin-top:.4rem}
            .trend-plot{position:relative}
            .trend svg{width:100%;height:170px;display:block;overflow:visible}
            .trend-head{display:flex;align-items:baseline;gap:.6rem;flex-wrap:wrap;
                        margin-bottom:.3rem;font-size:.85rem}

            /* Rótulos do eixo X. font-size em px porque o viewBox tem escala
               própria: em rem eles cresceriam junto com o estica do SVG. */
            .trend .eixo{fill:var(--muted);font-family:var(--f-mono);font-size:11px}

            /* Ponto do gráfico. O raio cresce no hover/foco para o alvo ficar
               maior que o desenho — 4px de raio é pouco para o dedo. */
            .trend .tp{cursor:pointer;transition:r .12s}
            .trend .tp:hover,.trend .tp:focus-visible{r:7}
            .trend .gap line{pointer-events:none}

            /* Tooltip. Posicionado em % pelo JS e centrado por translate, então
               acompanha o SVG em qualquer largura. */
            .tip{
              position:absolute;
              bottom:calc(100% - 12px);
              transform:translateX(-50%);
              display:flex;flex-direction:column;gap:.15rem;
              min-width:9rem;
              padding:.5rem .65rem;
              background:var(--panel-1);
              border:1px solid var(--neon-dim);
              box-shadow:0 0 18px rgba(0,0,0,.6);
              font-size:.78rem;line-height:1.35;
              pointer-events:none;
              z-index:4;
            }
            .tip b{font-family:var(--f-display);font-size:1.05rem}
            .tip[hidden]{display:none}
            .trend-label{font-family:var(--f-body);font-weight:600;
                         letter-spacing:.1em;text-transform:uppercase;font-size:.72rem}
            .trend-foot{display:flex;justify-content:space-between;
                        font-size:.7rem;margin-top:.2rem}
            /* Legenda da faixa na cor da faixa, casando com a linha tracejada. */
            .trend-faixa{color:var(--tier,var(--muted))}

            /* Botões de métrica: o .btn já existia no vocabulário e não tinha
               tela usando. O ativo é marcado por aria-pressed, que dá o estado
               ao leitor de tela em vez de só pintar. */
            .trend-tabs{display:flex;flex-wrap:wrap;gap:.4rem;margin-bottom:.9rem}
            .trend-tabs .btn{padding:.35rem .8rem;font-size:.72rem}
            .trend-tabs .btn[aria-pressed="true"]{
              border-color:var(--neon);
              background:rgba(176,38,255,.16);
              box-shadow:0 0 12px rgba(176,38,255,.35);
            }

            /* ───────────────────────────────────────────────────────────
               COACH PANEL
               Feedback consolidado, ordenado por gravidade. A cor da barra
               lateral e a do ícone vêm da mesma variável --sev, então trocar
               a gravidade de um item não exige mexer em dois lugares.
               ─────────────────────────────────────────────────────────── */
            .coach{list-style:none;padding:0;margin:0}
            .coach-item{
              --sev:var(--muted);
              display:flex;
              align-items:flex-start;
              gap:.7rem;
              padding:.75rem .9rem;
              margin-bottom:.5rem;
              background:rgba(255,255,255,.02);
              border:1px solid var(--line);
              border-left:2px solid var(--sev);
            }
            .coach-item.is-aviso  {--sev:var(--bad)}
            .coach-item.is-sucesso{--sev:var(--good)}
            .coach-item.is-info   {--sev:var(--neon-dim)}

            /* O ícone é o único elemento colorido pela gravidade. O texto fica
               em --text: dica é bloco de leitura, e neon em texto longo cansa. */
            .coach-item .ico{color:var(--sev);flex:0 0 auto;margin-top:.15rem}
            .coach-txt{min-width:0}
            .coach-txt p{margin:0;font-size:.95rem;line-height:1.5}
            .coach-meta{display:block;margin-top:.3rem;
                        font-size:.68rem;font-weight:600;letter-spacing:.12em;
                        text-transform:uppercase;color:var(--muted)}

            /* Banner do alerta mais grave, no topo da página. Um só — dois
               deixam de ser destaque e viram parede. */
            .coach-banner{
              display:flex;align-items:flex-start;gap:.75rem;
              margin-bottom:1.25rem;padding:.85rem 1rem;
              background:rgba(255,77,106,.07);
              border:1px solid var(--line);
              border-left:3px solid var(--bad);
            }
            .coach-banner .ico{color:var(--bad);flex:0 0 auto;margin-top:.1rem}
            .coach-banner p{margin:0;font-size:.95rem;line-height:1.45}
            .coach-banner a{display:inline-block;margin-top:.35rem;
                            font-size:.72rem;font-weight:600;letter-spacing:.1em;
                            text-transform:uppercase;color:var(--neon)}

            /* ───────────────────────────────────────────────────────────
               DESTAQUES DE RODADA
               O número do round fica à esquerda, grande, porque é por ele que
               a pessoa reencontra o momento na demo. O primeiro colocado é o
               único destacado: três realces lado a lado deixam de destacar.
               ─────────────────────────────────────────────────────────── */
            .hl{list-style:none;padding:0;margin:0;display:grid;gap:.5rem}
            .hl-item{
              --hl:var(--line);
              display:flex;align-items:center;gap:.9rem;
              padding:.7rem .9rem;
              background:rgba(255,255,255,.02);
              border:1px solid var(--hl);
            }
            .hl-item.is-topo{
              --hl:var(--neon);
              background:rgba(176,38,255,.07);
            }
            .hl-round{
              flex:0 0 auto;min-width:3.1rem;text-align:center;
              font-family:var(--f-mono);font-size:.62rem;letter-spacing:.14em;
              text-transform:uppercase;color:var(--muted);line-height:1.25
            }
            .hl-round b{display:block;font-family:var(--f-display);font-weight:900;
                        font-size:1.35rem;letter-spacing:0;color:var(--text)}
            .hl-item.is-topo .hl-round b{color:var(--neon)}
            .hl-corpo{min-width:0;flex:1 1 auto}
            .hl-titulo{font-family:var(--f-display);font-weight:700;
                       font-size:.95rem;letter-spacing:.02em}
            .hl-desc{margin:.2rem 0 0;font-size:.86rem;line-height:1.45;
                     color:var(--muted)}

            /* A pontuação é o critério da ordem: escondê-la deixaria "por que
               este round e não aquele" sem resposta na tela. */
            .hl-pts{flex:0 0 auto;text-align:right;font-family:var(--f-mono);
                    font-size:.62rem;letter-spacing:.1em;text-transform:uppercase;
                    color:var(--muted);line-height:1.3}
            .hl-pts b{display:block;font-family:var(--f-display);font-weight:700;
                      font-size:1rem;letter-spacing:0;color:var(--text)}

            /* Perdeu o round: o feito continua valendo, mas não é vitória.
               A barra à esquerda diz isso sem tingir o bloco inteiro. */
            .hl-item.is-perdido{box-shadow:inset 3px 0 0 var(--bad)}
            .hl-item.is-ganho  {box-shadow:inset 3px 0 0 var(--good)}

            /* ───────────────────────────────────────────────────────────
               RADAR
               Largura fixa e encolhível: o losango precisa de espaço para os
               rótulos, e espremê-lo mais que isso os faz colidir.
               ─────────────────────────────────────────────────────────── */
            .radar{flex:0 0 auto;width:min(300px,100%)}
            /* Sem overflow:visible. O viewBox já reserva a margem dos rótulos;
               se uma fonte de fallback vier mais larga, o texto é cortado em
               vez de invadir a coluna ao lado — falha visível, não confusa. */
            .radar svg{width:100%;height:auto;display:block}
            .radar-lbl{fill:var(--muted);font-family:var(--f-body);font-size:11px;
                       font-weight:600;letter-spacing:.08em;text-transform:uppercase}
            .radar-val{fill:var(--text);font-family:var(--f-mono);font-size:11px}

            /* ───────────────────────────────────────────────────────────
               SELO DE TÍTULO
               Chanfrado como o resto do vocabulário, e colorido pela
               CATEGORIA — o tom precisa chegar antes da leitura.
               ─────────────────────────────────────────────────────────── */
            .badge-award{
              --aw:var(--neon);
              --aw-soft:rgba(176,38,255,.14);
              display:inline-block;
              margin-left:.5rem;
              padding:.1rem .45rem;
              font-family:var(--f-body);font-size:.62rem;font-weight:700;
              letter-spacing:.1em;text-transform:uppercase;white-space:nowrap;
              color:var(--aw);
              background:var(--aw-soft);
              border:1px solid var(--aw);
              cursor:help;
              vertical-align:middle;
            }
            .badge-award.is-epico {--aw:#FFC53D;--aw-soft:rgba(255,197,61,.14)}
            .badge-award.is-neutro{--aw:var(--neon);--aw-soft:rgba(176,38,255,.14)}
            .badge-award.is-comico{--aw:#FF57C1;--aw-soft:rgba(255,87,193,.14)}

            /* Só o épico brilha. Se todos brilhassem, nenhum se destacaria. */
            .badge-award.is-epico{box-shadow:0 0 12px rgba(255,197,61,.35)}

            .badge-award.is-grande{font-size:.72rem;padding:.2rem .6rem;margin-left:0}

            /* ───────────────────────────────────────────────────────────
               SPARKLINE E BARRA DE K/D
               ─────────────────────────────────────────────────────────── */

            /* Ocupa a largura do card e altura fixa: a curva é forma, não
               escala — comparar dois cards pela altura seria enganoso, já que
               cada um normaliza pelo próprio mínimo e máximo. */
            .spark{display:block;width:100%;height:22px;margin-top:.5rem;opacity:.85}

            .kdbar{margin-top:.5rem}
            .kdbar-track{height:6px;display:flex;overflow:hidden;
                         background:var(--bad);border:1px solid var(--line)}
            .kdbar-k{display:block;height:100%;background:var(--good)}
            .kdbar-legend{display:flex;justify-content:space-between;
                          margin-top:.3rem;font-family:var(--f-mono);font-size:.68rem}

            /* ───────────────────────────────────────────────────────────
               BOTÃO
               Fundo escuro, borda neon, glow só no hover/foco — o estado de
               repouso não compete com os dados.
               ─────────────────────────────────────────────────────────── */
            .btn{
              display:inline-block;
              font-family:var(--f-body);font-weight:600;font-size:.85rem;
              letter-spacing:.1em;text-transform:uppercase;
              color:var(--text);text-decoration:none;
              background:rgba(176,38,255,.05);
              border:1px solid var(--neon-dim);
              padding:.6rem 1.3rem;
              cursor:pointer;
              transition:border-color .18s,box-shadow .18s,background .18s;
            }
            .btn:hover,.btn:focus-visible{
              border-color:var(--neon);
              background:rgba(176,38,255,.12);
              box-shadow:0 0 16px rgba(176,38,255,.45);
              outline:none;
            }

            /* ───────────────────────────────────────────────────────────
               ESTADOS SEMÂNTICOS DE TEXTO
               ─────────────────────────────────────────────────────────── */
            .up{color:var(--good)}
            .down{color:var(--bad)}
            .dim{color:var(--muted)}
            .ct{color:var(--ct)}
            .tr{color:var(--tr)}

__FUNDO3D_CSS__

            /* ───────────────────────────────────────────────────────────
               MOVIMENTO

               Uma curva só governa a página inteira: sai rápido e desacelera
               por muito tempo. É ela que produz a sensação de "fluido" — o
               elemento parece ter peso e assentar, em vez de deslizar a
               velocidade constante como um menu de sistema.

               O deslocamento de entrada é curto de propósito (12–18px). O que
               vende o movimento é a curva e o encadeamento, não a distância;
               entrada longa vira espera, e esta é uma página que a pessoa abre
               para ler um número.
               ─────────────────────────────────────────────────────────── */
            :root{
              --ease:cubic-bezier(.16,1,.3,1);
              --ease-curto:cubic-bezier(.4,0,.2,1);
              --t-rapido:140ms;
              --t-medio:280ms;
              --t-lento:560ms;
              --stagger:55ms;
            }

            /* Tudo daqui para baixo é condicional: quem pediu menos movimento
               recebe a página já assentada, nunca uma versão invisível
               esperando script. */
            @media (prefers-reduced-motion:no-preference){

              /* 1. Estrutura — os painéis, que já existem na marcação.
                 Revelados por IntersectionObserver conforme entram na tela. */
              .revelar{opacity:0;transform:translateY(18px)}
              .revelar.is-visivel{
                opacity:1;transform:none;
                transition:opacity var(--t-lento) var(--ease) var(--atraso,0ms),
                           transform var(--t-lento) var(--ease) var(--atraso,0ms);
              }

              /* 2. Conteúdo — cards, linhas e itens que o JS insere depois.
                 Animação na inserção, escalonada pelo índice. */
              .entrar{animation:entrar var(--t-lento) var(--ease) both;
                      animation-delay:var(--atraso,0ms)}

              @keyframes entrar{
                from{opacity:0;transform:translateY(12px) scale(.985)}
                to  {opacity:1;transform:none}
              }

              /* O cabeçalho não espera scroll: ele já está na tela quando a
                 página abre, e é o primeiro passo da sequência. */
              .hdr,.nav{animation:entrar var(--t-medio) var(--ease-curto) both}
              .nav{animation-delay:0ms}
              .hdr{animation-delay:60ms}
            }

            /* Números contados precisam de largura estável: sem isto o card
               "pula" a cada quadro, porque cada dígito tem largura diferente
               na Orbitron. */
            .chip .v,.record,.hl-pts b{font-variant-numeric:tabular-nums}

            /* Barras crescem a partir da origem em vez de aparecer prontas —
               o valor "chega", que é o mesmo gesto do número contado. */
            .bar > i,.kdbar-k{transition:width var(--t-lento) var(--ease)}

            /* Sinal de forma que acompanha toda cor de estado.
               É o que faz o indicador continuar legível para quem não
               distingue verde de vermelho — e, de quebra, para quem está no
               sol ou num monitor mal calibrado. Fica aria-hidden porque o
               significado já está no número ao lado; para o leitor de tela o
               glifo seria ruído. */
            .sig{font-family:var(--f-body);font-weight:700;font-size:.78em;
                 margin-right:.28em;letter-spacing:0}

            /* Foco sempre visível: a página é operável por teclado e o anel
               padrão do navegador some contra um fundo quase preto. */
            :focus-visible{outline:2px solid var(--focus);outline-offset:2px}

            /* ───────────────────────────────────────────────────────────
               MOVIMENTO REDUZIDO
               Scanlines com flicker e badge pulsando são exatamente o tipo
               de movimento que incomoda quem pede menos animação. A
               identidade sobrevive inteira sem eles.
               ─────────────────────────────────────────────────────────── */
            @media (prefers-reduced-motion:reduce){
              *,*::before,*::after{
                animation-duration:.001ms !important;
                animation-iteration-count:1 !important;
                transition-duration:.001ms !important;
              }
              body::after{opacity:.3}
            }
            """;

    /** Vocabulário completo: o tema com o CSS do fundo 3D costurado. */
    static final String CSS = BASE.replace("__FUNDO3D_CSS__", HudBackdrop.CSS);

}
