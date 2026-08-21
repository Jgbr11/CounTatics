package com.countatic.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Testes do login OpenID.
 *
 * <p>A propriedade que sustenta o login inteiro: <b>o {@code claimed_id} que
 * chega na URL não vale nada sem a confirmação da Steam</b>. Ele é um parâmetro
 * de query — qualquer pessoa digita o SteamID de outra ali. Os testes abaixo
 * cercam justamente as formas de tentar pular essa confirmação.</p>
 */
class SteamOpenIdServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    private RestClient.Builder builder;
    private MockRestServiceServer servidor;
    private SteamOpenIdService servico;

    @BeforeEach
    void preparar() {
        builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        servico = new SteamOpenIdService(builder.build());
    }

    private Map<String, String> retornoDe(String claimedId) {
        Map<String, String> p = new HashMap<>();
        p.put("openid.ns", "http://specs.openid.net/auth/2.0");
        p.put("openid.mode", "id_res");
        p.put("openid.claimed_id", claimedId);
        p.put("openid.identity", claimedId);
        p.put("openid.sig", "assinatura-qualquer");
        return p;
    }

    private void steamResponde(String corpo) {
        servidor.expect(requestTo("https://steamcommunity.com/openid/login"))
                .andRespond(withSuccess(corpo, MediaType.TEXT_PLAIN));
    }

    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("com a Steam confirmando, devolve o SteamID64")
    void confirmadoDevolveSteamId() {
        steamResponde("ns:http://specs.openid.net/auth/2.0\nis_valid:true\n");

        var r = servico.verificar(retornoDe(
                "https://steamcommunity.com/openid/id/" + STEAM_ID));

        assertThat(r).contains(STEAM_ID);
        servidor.verify();
    }

    /** O ataque central: forjar a volta sem passar pela Steam. */
    @Test
    @DisplayName("se a Steam não confirma, o login é recusado")
    void semConfirmacaoRecusa() {
        steamResponde("ns:http://specs.openid.net/auth/2.0\nis_valid:false\n");

        assertThat(servico.verificar(retornoDe(
                "https://steamcommunity.com/openid/id/" + STEAM_ID))).isEmpty();
    }

    /**
     * Um domínio que apenas começa com o nome certo não é o domínio certo.
     * Sem âncora no fim da expressão, isto passaria.
     */
    @Test
    @DisplayName("domínio parecido com o da Steam não passa")
    void dominioParecidoNaoPassa() {
        assertThat(servico.verificar(retornoDe(
                "https://steamcommunity.com.invasor.br/openid/id/" + STEAM_ID))).isEmpty();

        assertThat(servico.verificar(retornoDe(
                "https://steamcommunity.com/openid/id/" + STEAM_ID + "?x=1"))).isEmpty();

        // Nada é enviado à Steam: a recusa acontece antes.
        servidor.verify();
    }

    @Test
    @DisplayName("retorno sem claimed_id é recusado")
    void semClaimedIdRecusa() {
        assertThat(servico.verificar(new HashMap<>())).isEmpty();
        servidor.verify();
    }

    /** Steam fora do ar não é login válido — recusar é a única resposta segura. */
    @Test
    @DisplayName("falha de rede na verificação recusa o login")
    void falhaDeRedeRecusa() {
        servidor.expect(requestTo("https://steamcommunity.com/openid/login"))
                .andRespond(withServerError());

        assertThat(servico.verificar(retornoDe(
                "https://steamcommunity.com/openid/id/" + STEAM_ID))).isEmpty();
    }

    @Test
    @DisplayName("a URL de login leva o retorno e o realm corretos")
    void urlDeLogin() {
        String url = servico.urlDeLogin("https://countatic.exemplo.br/login/retorno");

        assertThat(url).startsWith("https://steamcommunity.com/openid/login?");
        assertThat(url).contains("openid.mode=checkid_setup");
        assertThat(url).contains("return_to=https%3A%2F%2Fcountatic.exemplo.br%2Flogin%2Fretorno");
        // O realm precisa conter o return_to, ou a Steam recusa o pedido.
        assertThat(url).contains("realm=https%3A%2F%2Fcountatic.exemplo.br%2F");
        assertThat(url).contains("identifier_select");
    }
}
