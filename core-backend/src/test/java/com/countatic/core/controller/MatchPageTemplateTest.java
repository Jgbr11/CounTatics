package com.countatic.core.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes da montagem da página da partida.
 *
 * <p>A página é montada por uma cadeia de {@code String.replace}, e esse tipo
 * de montagem falha em silêncio: um marcador com o nome errado não quebra a
 * compilação nem lança exceção — só entrega HTML com o marcador cru no meio.
 * Daí os testes olharem para o texto produzido, e não para o método.</p>
 */
class MatchPageTemplateTest {

    private static final String JSON = """
            {"mapName":"de_dust2","scoreCT":13,"scoreTR":9,"players":[]}""";

    @Test
    @DisplayName("nenhum marcador de montagem sobra no HTML final")
    void naoSobraMarcador() {
        String html = MatchPageTemplate.render(JSON);

        // Cobre erro de digitação em qualquer um dos marcadores de uma vez só,
        // inclusive nos que forem acrescentados depois deste teste.
        assertThat(html)
                .doesNotContainPattern("__[A-Z_]+__");
    }

    @Test
    @DisplayName("o tema compartilhado e as fontes entram na página")
    void embuteTemaEFontes() {
        String html = MatchPageTemplate.render(JSON);

        assertThat(html).contains("--neon:#B026FF");
        assertThat(html).contains("fonts.googleapis.com");
        assertThat(html).contains("Orbitron");
    }

    /**
     * A URL das fontes é escrita quebrada em várias linhas do text block e
     * remontada pela continuação {@code \\} de fim de linha. Se essa
     * continuação parar de funcionar, a URL ganha quebras de linha no meio e o
     * navegador pede um recurso que não existe — sem erro visível no servidor.
     */
    @Test
    @DisplayName("a URL das fontes fica numa linha só, com as três famílias")
    void urlDasFontesNaoQuebra() {
        String html = MatchPageTemplate.render(JSON);

        String linhaDaUrl = html.lines()
                .filter(l -> l.contains("fonts.googleapis.com/css2"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("link das fontes não encontrado"));

        assertThat(linhaDaUrl)
                .contains("family=Orbitron")
                .contains("family=Rajdhani")
                .contains("family=Share+Tech+Mono")
                .contains("display=swap")
                .endsWith(">");
    }

    @Test
    @DisplayName("os dados da partida entram no lugar do marcador")
    void embuteOsDados() {
        String html = MatchPageTemplate.render(JSON);

        assertThat(html).contains("const DATA = {\"mapName\":\"de_dust2\"");
    }

    /**
     * Um nome de jogador contendo {@code </script>} fecharia a tag mais cedo e
     * o resto do JSON viraria HTML executável. Quebrar a sequência é o que
     * impede isso.
     */
    @Test
    @DisplayName("fechamento de tag dentro do JSON é neutralizado")
    void neutralizaFechamentoDeScript() {
        String html = MatchPageTemplate.render(
                "{\"playerName\":\"</script><script>alert(1)</script>\"}");

        assertThat(html).doesNotContain("</script><script>alert(1)");
        assertThat(html).contains("<\\/script>");
    }
}
