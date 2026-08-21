package com.countatic.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Nome e avatar públicos de uma conta Steam.
 *
 * <p>O login OpenID entrega só o SteamID64 — um número. Quem transforma isso em
 * "JGBR11" com foto é a Web API, com a mesma chave que o match sharing já usa.</p>
 *
 * <p>É <b>complemento, nunca requisito</b>: se a chamada falhar, o login segue e
 * o jogador aparece pelo SteamID até a próxima entrada. Bloquear a entrada
 * porque o avatar não carregou seria trocar um problema cosmético por um
 * impedimento.</p>
 */
@Slf4j
@Service
public class SteamProfileService {

    private static final String RESUMO_URL =
            "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=%s&steamids=%s";

    private final RestClient rest;
    private final String chave;

    public SteamProfileService(@Qualifier("valveRestClient") RestClient rest,
                               @Value("${steam.web-api-key:}") String chave) {
        this.rest = rest;
        this.chave = chave;
    }

    /** Nome de exibição e avatar, quando a Steam responde. */
    public Optional<Perfil> buscar(String steamId64) {
        if (chave == null || chave.isBlank()) return Optional.empty();

        try {
            JsonNode raiz = rest.get()
                    .uri(String.format(RESUMO_URL, enc(chave), enc(steamId64)))
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode jogadores = raiz == null ? null : raiz.path("response").path("players");
            if (jogadores == null || !jogadores.isArray() || jogadores.isEmpty()) {
                return Optional.empty();
            }

            JsonNode p = jogadores.get(0);
            String nome = p.path("personaname").asText(null);
            // avatarfull é 184x184 — a página usa a imagem pequena, mas guardar
            // a maior evita ter de buscar de novo se o layout crescer.
            String avatar = p.path("avatarfull").asText(null);

            if (nome == null || nome.isBlank()) return Optional.empty();
            return Optional.of(new Perfil(nome, avatar));

        } catch (Exception e) {
            log.warn("Não foi possível ler o perfil Steam de {}: {}", steamId64, e.getMessage());
            return Optional.empty();
        }
    }

    public record Perfil(String nome, String avatarUrl) {
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
