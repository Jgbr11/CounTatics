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
    static final String CSS = """
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
              --magenta:#FF2E9A;
              --cyan:#00F0FF;

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
            .panel-head::after{content:"";flex:1;height:1px;background:var(--line)}

            /* ───────────────────────────────────────────────────────────
               CHIP DE MÉTRICA
               Número grande em Orbitron com glow; label pequena e apagada.
               O glow vive aqui e no badge — em mais nenhum lugar.
               ─────────────────────────────────────────────────────────── */
            .chips{display:grid;gap:.7rem;
                   grid-template-columns:repeat(auto-fill,minmax(178px,1fr))}
            .chip{background:var(--line);padding:1px}
            .chip > .cut-in{background:var(--tint);padding:.75rem .85rem}
            .chip .k{font-size:.68rem;font-weight:600;letter-spacing:.13em;
                     text-transform:uppercase;color:var(--muted);
                     margin-bottom:.35rem;line-height:1.3}
            .chip .v{font-family:var(--f-display);font-weight:700;font-size:1.45rem;
                     letter-spacing:.02em;color:var(--text);
                     text-shadow:0 0 14px rgba(176,38,255,.45)}

            /* ───────────────────────────────────────────────────────────
               BADGE DE RANK
               Circular, anel neon, pulsação lenta. É a única forma redonda
               da interface — por isso chama atenção sem precisar de tamanho.
               ─────────────────────────────────────────────────────────── */
            .badge{
              display:grid;
              place-items:center;
              width:78px;height:78px;
              flex:0 0 auto;
              border-radius:50%;
              background:radial-gradient(circle at 50% 45%,
                         rgba(176,38,255,.20), rgba(176,38,255,.03) 70%);
              border:1px solid var(--neon);
              animation:hud-pulse 2.6s ease-in-out infinite;
              text-align:center;
            }
            .badge .n{font-family:var(--f-display);font-weight:900;font-size:1.1rem;
                      letter-spacing:.02em;line-height:1;
                      text-shadow:0 0 12px rgba(176,38,255,.7)}
            .badge .t{font-size:.6rem;font-weight:600;letter-spacing:.12em;
                      text-transform:uppercase;color:var(--muted);margin-top:.2rem}

            @keyframes hud-pulse{
              0%,100%{box-shadow:0 0 10px rgba(176,38,255,.30),
                                 inset 0 0 10px rgba(176,38,255,.12)}
              50%    {box-shadow:0 0 20px rgba(176,38,255,.55),
                                 inset 0 0 14px rgba(176,38,255,.20)}
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
            .bar > i.is-up{background:linear-gradient(90deg,#0b7f88,var(--cyan));
                           box-shadow:0 0 10px rgba(0,240,255,.45)}
            .bar > i.is-down{background:linear-gradient(90deg,#8f1a57,var(--magenta));
                             box-shadow:0 0 10px rgba(255,46,154,.45)}

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
            .items li.is-up{border-left-color:var(--cyan)}
            .items li.is-down{border-left-color:var(--magenta)}

            .note{background:var(--tint);border:1px solid var(--line);
                  border-left:2px solid var(--neon-dim);
                  padding:.7rem .85rem;font-size:.92rem;color:var(--muted)}

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
            .up{color:var(--cyan)}
            .down{color:var(--magenta)}
            .dim{color:var(--muted)}

            /* Foco sempre visível: a página é operável por teclado e o anel
               padrão do navegador some contra um fundo quase preto. */
            :focus-visible{outline:2px solid var(--cyan);outline-offset:2px}

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
}
