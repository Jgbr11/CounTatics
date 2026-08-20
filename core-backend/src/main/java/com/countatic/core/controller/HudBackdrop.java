package com.countatic.core.controller;

/**
 * Fundo 3D das telas: uma malha em wireframe que ondula atrás do conteúdo.
 *
 * <p><b>Por que WebGL cru e não uma biblioteca:</b> Three.js resolveria isto em
 * vinte linhas, mas custaria um CDN — e as telas são HTML autocontido servido
 * pelo próprio container, sem origem externa e sem build. Embutir a biblioteca
 * no HTML colocaria centenas de kilobytes em toda visita para desenhar um
 * fundo. O que precisamos aqui é uma projeção em perspectiva e uma onda, e as
 * duas cabem num shader de vinte linhas.</p>
 *
 * <p><b>Por que geometria de verdade e não um efeito de tela:</b> a alternativa
 * seria desenhar uma grade em perspectiva no fragment shader, o que dá um
 * desenho parado com brilho animado. Uma malha real de vértices, deformada no
 * vertex shader, tem relevo de fato — as linhas se cruzam e se ocultam como
 * geometria, que é o que separa "3D" de "textura que imita 3D".</p>
 *
 * <p><b>O que ela remete:</b> a malha de um radar, não o pôr do sol synthwave.
 * A grade recuando até um sol laranja é a resposta padrão de cyberpunk e
 * apareceria igual em qualquer site; um relevo em wireframe roxo é o
 * instrumento de leitura de terreno que o assunto desta página — mapa de CS2 —
 * realmente evoca.</p>
 *
 * <p>Três guardas obrigatórias, porque isto roda em toda visita e no celular
 * de quem acabou de jogar: sem WebGL o canvas se remove e a página fica como
 * estava; com {@code prefers-reduced-motion} desenha <b>um quadro só</b> (o
 * relevo continua lá, parado); e com a aba em segundo plano o laço para.</p>
 */
final class HudBackdrop {

    private HudBackdrop() {
    }

    /** Vai no {@link HudTheme#CSS}. */
    static final String CSS = """
            /* ───────────────────────────────────────────────────────────
               FUNDO 3D

               Camada 0, junto com a grade fina: abaixo das scanlines (1) e
               do conteúdo (2).

               A tentação era z-index:-1, para a malha ficar atrás até da
               grade. Isso exigiria tirar o fundo do body e passá-lo ao html,
               e a propagação de background da raiz não sobrevive a todo
               compositor — num teste headless a página inteira ficou branca,
               com o CSS correto. Não vale arriscar o fundo da página por uma
               ordem de camada que quase ninguém distingue.
               ─────────────────────────────────────────────────────────── */
            .fundo3d{
              position:fixed;
              inset:0;
              z-index:0;
              display:block;
              width:100%;
              height:100%;
              pointer-events:none;
            }
            """;

    /** Vai no fim do {@code <script>}, depois dos componentes. */
    static final String JS = """
            // ═══════════════════════════════════════════════════════════
            //  Fundo 3D — malha de radar em WebGL
            // ═══════════════════════════════════════════════════════════

            (function fundo3d() {

              const canvas = document.createElement("canvas");
              canvas.className = "fundo3d";
              // Decoração pura: não entra na árvore de acessibilidade e não
              // recebe ponteiro.
              canvas.setAttribute("aria-hidden", "true");
              document.body.insertBefore(canvas, document.body.firstChild);

              const gl = canvas.getContext("webgl", {
                alpha: true, antialias: true, depth: false,
                // Fundo decorativo não justifica acordar a GPU dedicada de um
                // notebook e comer bateria de quem só quer ler o relatório.
                powerPreference: "low-power",
              });

              // Sem WebGL a página continua exatamente como era: fundo sólido,
              // grade e scanlines. O fundo é adorno, nunca requisito.
              if (!gl) { canvas.remove(); return; }

              // ── Malha ────────────────────────────────────────────────
              // Linhas em X e em Z. A densidade é modesta de propósito: mais
              // linhas não deixam o relevo mais legível a 10% de opacidade,
              // só custam vértices.
              const COLUNAS = 44, FILAS = 34;
              const pontos = [];

              for (let f = 0; f < FILAS; f++) {
                const z = f / (FILAS - 1);
                for (let c = 0; c < COLUNAS - 1; c++) {
                  const x1 = (c / (COLUNAS - 1)) * 2 - 1;
                  const x2 = ((c + 1) / (COLUNAS - 1)) * 2 - 1;
                  pontos.push(x1, z, x2, z);
                }
              }
              for (let c = 0; c < COLUNAS; c++) {
                const x = (c / (COLUNAS - 1)) * 2 - 1;
                for (let f = 0; f < FILAS - 1; f++) {
                  pontos.push(x, f / (FILAS - 1), x, (f + 1) / (FILAS - 1));
                }
              }

              const vertices = new Float32Array(pontos);
              const buffer = gl.createBuffer();
              gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
              gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.STATIC_DRAW);

              // ── Shaders ──────────────────────────────────────────────
              const VS = `
                attribute vec2 aGrade;

                uniform float uFase;
                uniform vec2  uMouse;
                uniform float uAspecto;

                varying float vProf;

                void main() {
                  float x = aGrade.x;   // -1 a 1, largura da malha
                  float z = aGrade.y;   //  0 a 1, do observador ao horizonte

                  // Relevo, em unidades de mundo. Três ondas de períodos que
                  // não fecham entre si: o padrão nunca se repete de forma
                  // reconhecível. Elas correm na direção do observador — é daí
                  // que vem o movimento, já que a geometria fica ancorada
                  // (deslocar os vértices em Z abriria uma emenda no ponto
                  // onde a malha reinicia).
                  float h = sin(x * 1.6 + uFase * 0.9) * 0.38
                          + sin(z * 6.0 - uFase * 1.5) * 0.28
                          + sin((x + z * 2.0) * 3.1 + uFase * 0.6) * 0.16;

                  // Câmera de verdade: 1,3 unidade acima do plano, olhando
                  // para o horizonte. A profundidade cresce com o quadrado de
                  // z, o que põe mais linhas perto — onde o relevo se lê — e
                  // comprime o fundo, como perspectiva real faz.
                  float Zw = 0.9 + z * z * 13.0;

                  float X = x * 7.0 + uMouse.x * 0.9;
                  float Y = h - 1.30 + uMouse.y * 0.25;

                  const float f = 1.1;   // distância focal

                  float sx = (X * f) / Zw;
                  // Horizonte logo acima da metade da tela: abaixo disso a
                  // malha só aparecia nos cantos inferiores, escondida pelo
                  // painel de conteúdo.
                  float sy = (Y * f) / Zw + 0.02;

                  gl_Position = vec4(sx / uAspecto, sy, 0.0, 1.0);
                  vProf = z;
                }
              `;

              const FS = `
                precision mediump float;

                varying float vProf;
                uniform float uForca;

                void main() {
                  // Some nas duas pontas: perto, para a malha não subir por
                  // cima do conteúdo; longe, como névoa de profundidade.
                  float perto = smoothstep(0.04, 0.30, vProf);
                  float longe = 1.0 - smoothstep(0.72, 1.0, vProf);
                  float a = perto * longe * uForca;

                  // Roxo da marca perto, virando ciano nas cristas distantes —
                  // o mesmo par que o resto do HUD usa para foco.
                  vec3 cor = mix(vec3(0.69, 0.15, 1.0),
                                 vec3(0.00, 0.94, 1.0),
                                 smoothstep(0.35, 0.95, vProf) * 0.55);

                  // A cor sai pura e o alfa faz o trabalho uma vez só. Com
                  // blendFunc(SRC_ALPHA, ONE), escrever cor*a aqui aplicaria o
                  // alfa duas vezes — a 0,2 de força sobrava 4% do brilho, e a
                  // malha ficava praticamente invisível.
                  gl_FragColor = vec4(cor, a);
                }
              `;

              function compilar(tipo, fonte) {
                const s = gl.createShader(tipo);
                gl.shaderSource(s, fonte);
                gl.compileShader(s);
                if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) return null;
                return s;
              }

              const vs = compilar(gl.VERTEX_SHADER, VS);
              const fs = compilar(gl.FRAGMENT_SHADER, FS);
              if (!vs || !fs) { canvas.remove(); return; }

              const prog = gl.createProgram();
              gl.attachShader(prog, vs);
              gl.attachShader(prog, fs);
              gl.linkProgram(prog);
              if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) { canvas.remove(); return; }

              gl.useProgram(prog);

              const aGrade   = gl.getAttribLocation(prog, "aGrade");
              const uFase    = gl.getUniformLocation(prog, "uFase");
              const uMouse   = gl.getUniformLocation(prog, "uMouse");
              const uAspecto = gl.getUniformLocation(prog, "uAspecto");
              const uForca   = gl.getUniformLocation(prog, "uForca");

              gl.enableVertexAttribArray(aGrade);
              gl.vertexAttribPointer(aGrade, 2, gl.FLOAT, false, 0, 0);

              // Aditivo: linhas que se cruzam somam brilho, que é como neon se
              // comporta. Com alpha comum os cruzamentos ficariam iguais ao
              // resto e a malha perderia o relevo.
              gl.enable(gl.BLEND);
              gl.blendFunc(gl.SRC_ALPHA, gl.ONE);

              // ── Estado ───────────────────────────────────────────────
              // A força é baixa de propósito: o fundo tem de dar sinal de vida
              // sem nunca disputar com um número na tela.
              const FORCA = 0.20;

              let mouseX = 0, mouseY = 0, alvoX = 0, alvoY = 0;
              let fase = 0, impulso = 0, ultimoScroll = window.scrollY;

              function dimensionar() {
                // Teto no devicePixelRatio: num monitor 3x, um fundo em
                // resolução nativa é nove vezes o trabalho para um enfeite.
                const dpr = Math.min(window.devicePixelRatio || 1, 1.25);
                const l = Math.floor(canvas.clientWidth * dpr);
                const a = Math.floor(canvas.clientHeight * dpr);
                if (canvas.width === l && canvas.height === a) return;
                canvas.width = l;
                canvas.height = a;
                gl.viewport(0, 0, l, a);
                gl.uniform1f(uAspecto, Math.max(l / Math.max(a, 1), 0.5));
              }

              function desenhar() {
                gl.clearColor(0, 0, 0, 0);
                gl.clear(gl.COLOR_BUFFER_BIT);
                gl.uniform1f(uFase, fase);
                gl.uniform2f(uMouse, mouseX, mouseY);
                gl.uniform1f(uForca, FORCA);
                gl.drawArrays(gl.LINES, 0, vertices.length / 2);
              }

              dimensionar();

              // Movimento reduzido: um quadro só. O relevo continua lá — o que
              // desaparece é a ondulação, que é justamente o que incomoda.
              if (!MOVIMENTO_OK) {
                desenhar();
                window.addEventListener("resize", () => { dimensionar(); desenhar(); });
                return;
              }

              window.addEventListener("pointermove", e => {
                alvoX = (e.clientX / window.innerWidth) * 2 - 1;
                alvoY = (e.clientY / window.innerHeight) * 2 - 1;
              }, { passive: true });

              window.addEventListener("scroll", () => {
                // A velocidade do scroll vira impulso na onda: a malha
                // acelera enquanto a pessoa rola e desacelera sozinha.
                const d = window.scrollY - ultimoScroll;
                ultimoScroll = window.scrollY;
                impulso += Math.min(Math.abs(d), 60) * 0.0016;
              }, { passive: true });

              let anterior = 0;

              function laco(agora) {
                requestAnimationFrame(laco);

                // Aba em segundo plano não desenha. O rAF já costuma parar,
                // mas em janela dividida ele continua rodando.
                if (document.hidden) { anterior = agora; return; }

                const dt = Math.min((agora - anterior) / 1000, 0.05);
                anterior = agora;

                // Perseguição amortecida: o parallax chega ao ponteiro com
                // atraso, que é o que faz parecer profundidade e não recorte.
                mouseX += (alvoX - mouseX) * 0.045;
                mouseY += (alvoY - mouseY) * 0.045;

                impulso *= 0.94;
                fase += dt * (0.32 + impulso);

                dimensionar();
                desenhar();
              }

              requestAnimationFrame(laco);
            })();
            """;
}
