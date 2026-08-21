package com.countatic.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Login com a Steam via OpenID 2.0.
 *
 * <p>A Steam nunca migrou para OAuth: o que ela oferece é OpenID 2.0, um
 * protocolo aposentado em 2014 e que nenhuma biblioteca moderna do Spring
 * implementa. Por isso está escrito à mão — e, ao contrário do que o nome
 * sugere, é pequeno: um redirecionamento de ida e uma verificação de volta.</p>
 *
 * <p><b>O que ele dá e o que não dá:</b> devolve o SteamID64 de quem entrou,
 * verificado pela própria Steam. Não devolve e-mail, nome nem avatar — esses
 * saem de uma chamada separada à Web API, que já temos por causa do
 * match sharing.</p>
 *
 * <p><b>A regra que não pode ser quebrada:</b> o {@code claimed_id} que volta na
 * URL <b>não é prova de nada</b> — é um parâmetro de query, e qualquer um pode
 * digitar o SteamID de outra pessoa ali. A prova é a segunda etapa: devolver
 * todos os parâmetros à Steam com {@code openid.mode=check_authentication} e
 * ouvir dela {@code is_valid:true}. Sem essa etapa, o login seria só um
 * formulário onde se declara quem se é.</p>
 */
@Slf4j
@Service
public class SteamOpenIdService {

    /** Endpoint OpenID da Steam, para onde o usuário é enviado e de onde a resposta é conferida. */
    private static final String STEAM_OPENID = "https://steamcommunity.com/openid/login";

    /** Namespace fixo do OpenID 2.0. */
    private static final String NS = "http://specs.openid.net/auth/2.0";

    /**
     * Identificador de "qualquer usuário" — quem responde qual é o identificador
     * concreto é a Steam, depois do login.
     */
    private static final String IDENTIFIER_SELECT =
            "http://specs.openid.net/auth/2.0/identifier_select";

    /**
     * O identificador devolvido pela Steam.
     *
     * <p>Ancorado nas duas pontas de propósito: sem o {@code ^} e o {@code $},
     * um domínio parecido com {@code steamcommunity.com.exemplo.br} passaria.</p>
     */
    private static final Pattern CLAIMED_ID =
            Pattern.compile("^https://steamcommunity\\.com/openid/id/(\\d{17})$");

    private final RestClient rest;

    public SteamOpenIdService(@Qualifier("valveRestClient") RestClient rest) {
        this.rest = rest;
    }

    /**
     * URL para onde o navegador deve ser mandado para começar o login.
     *
     * @param urlDeRetorno endereço absoluto do nosso callback
     */
    public String urlDeLogin(String urlDeRetorno) {
        String base = urlDeRetorno.substring(0, urlDeRetorno.indexOf('/', 8) + 1);

        return STEAM_OPENID
                + "?openid.ns=" + enc(NS)
                + "&openid.mode=checkid_setup"
                + "&openid.return_to=" + enc(urlDeRetorno)
                // O "realm" é o que a Steam mostra ao usuário como o site que
                // está pedindo o login. Tem de conter o return_to.
                + "&openid.realm=" + enc(base)
                + "&openid.identity=" + enc(IDENTIFIER_SELECT)
                + "&openid.claimed_id=" + enc(IDENTIFIER_SELECT);
    }

    /**
     * Confere a resposta da Steam e devolve o SteamID64 de quem entrou.
     *
     * @param parametros todos os {@code openid.*} que chegaram na query do callback
     * @return o SteamID64, ou vazio se a Steam não confirmar
     */
    public Optional<String> verificar(Map<String, String> parametros) {
        String claimed = parametros.get("openid.claimed_id");
        if (claimed == null) {
            log.warn("Retorno de login sem claimed_id");
            return Optional.empty();
        }

        Matcher m = CLAIMED_ID.matcher(claimed);
        if (!m.matches()) {
            log.warn("claimed_id fora do formato esperado: {}", claimed);
            return Optional.empty();
        }
        String steamId64 = m.group(1);

        // Devolve tudo o que veio, trocando só o modo. A assinatura cobre um
        // conjunto de campos que a Steam escolheu; reenviar exatamente o que
        // chegou é o que permite a ela recalcular e conferir.
        MultiValueMap<String, String> corpo = new LinkedMultiValueMap<>();
        parametros.forEach((chave, valor) -> {
            if (chave.startsWith("openid.")) corpo.add(chave, valor);
        });
        corpo.set("openid.mode", "check_authentication");

        String resposta;
        try {
            resposta = rest.post()
                    .uri(STEAM_OPENID)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(corpo)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // Steam fora do ar não é login inválido — mas também não é login
            // válido. Recusar é a única resposta segura.
            log.warn("Falha ao verificar login na Steam: {}", e.getMessage());
            return Optional.empty();
        }

        if (resposta == null || !resposta.contains("is_valid:true")) {
            log.warn("Steam recusou a verificação do login de {}", steamId64);
            return Optional.empty();
        }

        return Optional.of(steamId64);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
