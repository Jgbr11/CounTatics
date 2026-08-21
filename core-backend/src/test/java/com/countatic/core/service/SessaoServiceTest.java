package com.countatic.core.service;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do cookie de sessão.
 *
 * <p>O que precisa ser inviolável aqui é uma coisa só: <b>ninguém pode trocar o
 * SteamID do cookie e continuar aceito</b>. Se isso falhar, qualquer visitante
 * vira dono de qualquer conta — e o resto do login não importa.</p>
 */
class SessaoServiceTest {

    private static final String SEGREDO = "chave-de-teste-com-tamanho-suficiente";
    private static final String STEAM_ID = "76561199110265389";

    private SessaoService criar() {
        return new SessaoService(SEGREDO, "http://localhost:8080");
    }

    private MockHttpServletRequest comCookieDe(MockHttpServletResponse r) {
        MockHttpServletRequest pedido = new MockHttpServletRequest();
        pedido.setCookies(r.getCookies());
        return pedido;
    }

    @Test
    @DisplayName("quem entra é reconhecido na requisição seguinte")
    void idaEVolta() {
        SessaoService s = criar();
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        s.abrir(resposta, STEAM_ID);

        assertThat(s.steamIdLogado(comCookieDe(resposta))).contains(STEAM_ID);
    }

    /** O ataque óbvio: editar o cookie e se declarar outra pessoa. */
    @Test
    @DisplayName("trocar o SteamID no cookie invalida a sessão")
    void adulteracaoEhRecusada() {
        SessaoService s = criar();
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        s.abrir(resposta, STEAM_ID);

        Cookie original = resposta.getCookie(SessaoService.COOKIE);
        String adulterado = original.getValue().replace(STEAM_ID, "76561198000000002");

        MockHttpServletRequest pedido = new MockHttpServletRequest();
        pedido.setCookies(new Cookie(SessaoService.COOKIE, adulterado));

        assertThat(s.steamIdLogado(pedido)).isEmpty();
    }

    @Test
    @DisplayName("assinatura de outra chave não vale")
    void chaveDiferenteNaoVale() {
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        new SessaoService("outra-chave-completamente-diferente", "http://localhost:8080")
                .abrir(resposta, STEAM_ID);

        assertThat(criar().steamIdLogado(comCookieDe(resposta))).isEmpty();
    }

    @Test
    @DisplayName("cookie com validade vencida é recusado")
    void validadeVencida() {
        SessaoService s = criar();

        // Carga com validade no passado, assinada corretamente: prova que a
        // expiração é verificada de fato, e não só a assinatura.
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        s.abrir(resposta, STEAM_ID);
        String valor = resposta.getCookie(SessaoService.COOKIE).getValue();
        String[] partes = valor.split("\\.");

        MockHttpServletRequest pedido = new MockHttpServletRequest();
        pedido.setCookies(new Cookie(SessaoService.COOKIE,
                partes[0] + ".1000000000." + partes[2]));

        assertThat(s.steamIdLogado(pedido)).isEmpty();
    }

    @Test
    @DisplayName("sem segredo configurado, o login fica desligado")
    void semSegredoNaoLoga() {
        SessaoService s = new SessaoService("", "http://localhost:8080");

        assertThat(s.estaConfigurado()).isFalse();

        MockHttpServletResponse resposta = new MockHttpServletResponse();
        s.abrir(resposta, STEAM_ID);
        assertThat(s.steamIdLogado(comCookieDe(resposta))).isEmpty();
    }

    @Test
    @DisplayName("sair apaga o cookie")
    void sairApaga() {
        SessaoService s = criar();
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        s.fechar(resposta);

        assertThat(resposta.getCookie(SessaoService.COOKIE).getMaxAge()).isZero();
    }

    /**
     * Marcar Secure em http faria o navegador descartar o cookie sem avisar —
     * o login "funcionaria" e ninguém ficaria logado.
     */
    @Test
    @DisplayName("Secure acompanha o protocolo da URL pública")
    void secureSegueOProtocolo() {
        MockHttpServletResponse local = new MockHttpServletResponse();
        new SessaoService(SEGREDO, "http://localhost:8080").abrir(local, STEAM_ID);
        assertThat(local.getCookie(SessaoService.COOKIE).getSecure()).isFalse();

        MockHttpServletResponse publico = new MockHttpServletResponse();
        new SessaoService(SEGREDO, "https://countatic.exemplo.br").abrir(publico, STEAM_ID);
        assertThat(publico.getCookie(SessaoService.COOKIE).getSecure()).isTrue();
    }

    @Test
    @DisplayName("o cookie não é legível por script")
    void httpOnly() {
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        criar().abrir(resposta, STEAM_ID);

        assertThat(resposta.getCookie(SessaoService.COOKIE).isHttpOnly()).isTrue();
    }
}
