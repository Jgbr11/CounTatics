package com.countatic.core.controller;

/**
 * Tela de estado (404, 500) com a identidade visual do sistema.
 *
 * <p>Compartilhada porque a página da partida e o painel do jogador falham
 * pelos mesmos motivos e devem falhar com a mesma cara. Enquanto vivia privada
 * num controller, o segundo a precisar dela teria copiado.</p>
 */
final class StatusPage {

    private StatusPage() {
    }

    /**
     * @param code    o número grande — é o elemento de destaque
     * @param title   o que aconteceu, em uma linha
     * @param message a explicação, em texto corrente: neon em bloco de texto cansa
     */
    static String render(String code, String title, String message) {
        return HTML
                .replace("__CODE__", escape(code))
                .replace("__TITLE__", escape(title))
                .replace("__MESSAGE__", escape(message));
    }

    /**
     * Escapa o que vai para dentro do HTML.
     *
     * <p>Hoje as chamadas passam literais nossos, então nada aqui é alcançável
     * por entrada externa. É defesa contra o futuro: no dia em que alguém
     * repassar o token da URL para a mensagem, o escape já está no caminho.</p>
     */
    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String HTML = ("""
            <!doctype html>
            <html lang="pt-BR">
            <head>
            __META__
              <title>__TITLE__ — CounTatic</title>
            __FONTS__
              <style>
            __THEME_CSS__
                .wrap{min-height:100vh;display:grid;place-items:center;text-align:center}
                .brand{font-family:var(--f-mono);font-size:.72rem;letter-spacing:.22em;
                       text-transform:uppercase;color:var(--neon);margin-bottom:1.5rem}
                .code{font-family:var(--f-display);font-weight:900;
                      font-size:clamp(4rem,16vw,7rem);line-height:1;
                      letter-spacing:.06em;color:var(--neon);
                      text-shadow:0 0 28px rgba(176,38,255,.55)}
                .msg{color:var(--muted);margin:1rem auto 0;max-width:34ch}
                hr{border:0;height:1px;background:var(--line);margin:1.4rem auto;width:120px}
              </style>
            </head>
            <body>
              <main class="wrap">
                <div>
                  <div class="brand">CounTatic</div>
                  <div class="code">__CODE__</div>
                  <hr>
                  <h1>__TITLE__</h1>
                  <p class="msg">__MESSAGE__</p>
                </div>
              </main>
            </body>
            </html>
            """)
            // Resolvido uma vez, na carga da classe: o tema não muda entre
            // requisições, e só __CODE__/__TITLE__/__MESSAGE__ variam.
            .replace("__META__", HudTheme.META)
            .replace("__FONTS__", HudTheme.FONTS)
            .replace("__THEME_CSS__", HudTheme.CSS);
}
