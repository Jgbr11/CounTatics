package com.countatic.core.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Sessão do usuário logado, num cookie assinado.
 *
 * <p><b>Por que não a sessão do Spring:</b> a sessão em memória morre a cada
 * restart do container, e este projeto sobe e desce com frequência — todo mundo
 * seria deslogado a cada deploy. Guardar sessões numa tabela resolveria isso e
 * traria um cadastro para manter, expirar e limpar. Um cookie assinado não
 * guarda estado nenhum: o servidor confere a assinatura e sabe de quem é.</p>
 *
 * <p><b>O que o cookie carrega:</b> {@code steamId64.validade.assinatura}. Nada
 * secreto — o SteamID64 é público. O que a assinatura impede é <b>trocar o
 * valor</b>: sem a chave, mudar o SteamID invalida o HMAC.</p>
 *
 * <p><b>A chave:</b> vem de {@code SESSION_SECRET}. Sem ela o login fica
 * desligado, e é de propósito: um valor padrão embutido no código seria público
 * junto com o repositório, e qualquer pessoa poderia assinar um cookie
 * dizendo-se dona de qualquer conta.</p>
 */
@Slf4j
@Service
public class SessaoService {

    public static final String COOKIE = "countatic_sessao";

    /** Quanto tempo o login dura. Um mês: é um app de acompanhar partidas, não um banco. */
    private static final Duration VALIDADE = Duration.ofDays(30);

    private static final String ALGORITMO = "HmacSHA256";

    private final byte[] chave;
    private final boolean https;

    public SessaoService(@Value("${countatic.session-secret:}") String segredo,
                         @Value("${countatic.web-base-url:}") String urlBase) {
        this.chave = (segredo == null ? "" : segredo).getBytes(StandardCharsets.UTF_8);
        // Cookie Secure só funciona sobre HTTPS; marcá-lo em desenvolvimento
        // (http://localhost) faria o navegador descartar o login silenciosamente.
        this.https = urlBase != null && urlBase.startsWith("https://");

        if (!estaConfigurado()) {
            log.warn("countatic.session-secret vazio — o login com a Steam fica desligado. "
                    + "Defina SESSION_SECRET no .env para habilitar.");
        }
    }

    /** Sem chave não há login: é melhor não ter do que ter um falsificável. */
    public boolean estaConfigurado() {
        return chave.length >= 16;
    }

    /** Grava o cookie de sessão na resposta. */
    public void abrir(HttpServletResponse resposta, String steamId64) {
        // Sem chave não há assinatura possível. Sair calado é melhor que
        // estourar: quem chama já foi barrado antes por estaConfigurado(), e
        // uma exceção aqui viraria erro 500 numa página de login.
        if (!estaConfigurado()) return;

        long expira = Instant.now().plus(VALIDADE).getEpochSecond();
        String carga = steamId64 + "." + expira;
        String valor = carga + "." + assinar(carga);

        Cookie c = new Cookie(COOKIE, valor);
        c.setHttpOnly(true);              // fora do alcance de qualquer script
        c.setSecure(https);
        c.setPath("/");
        c.setMaxAge((int) VALIDADE.toSeconds());
        c.setAttribute("SameSite", "Lax"); // o retorno da Steam é uma navegação GET
        resposta.addCookie(c);
    }

    /** Apaga o cookie. */
    public void fechar(HttpServletResponse resposta) {
        Cookie c = new Cookie(COOKIE, "");
        c.setHttpOnly(true);
        c.setSecure(https);
        c.setPath("/");
        c.setMaxAge(0);
        resposta.addCookie(c);
    }

    /** SteamID64 de quem está logado, ou vazio. */
    public Optional<String> steamIdLogado(HttpServletRequest pedido) {
        if (!estaConfigurado() || pedido.getCookies() == null) return Optional.empty();

        for (Cookie c : pedido.getCookies()) {
            if (!COOKIE.equals(c.getName())) continue;

            String[] partes = c.getValue().split("\\.");
            if (partes.length != 3) continue;

            String carga = partes[0] + "." + partes[1];

            // Comparação em tempo constante: comparar assinatura com equals
            // vaza, pelo tempo de resposta, quantos bytes iniciais acertaram.
            if (!MessageDigest.isEqual(assinar(carga).getBytes(StandardCharsets.UTF_8),
                                       partes[2].getBytes(StandardCharsets.UTF_8))) {
                continue;
            }

            try {
                if (Long.parseLong(partes[1]) < Instant.now().getEpochSecond()) continue;
            } catch (NumberFormatException e) {
                continue;
            }

            return Optional.of(partes[0]);
        }
        return Optional.empty();
    }

    private String assinar(String carga) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(chave, ALGORITMO));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(carga.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar a sessão", e);
        }
    }
}
