package com.countatic.core.controller;

import com.countatic.core.entity.Player;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.service.SessaoService;
import com.countatic.core.service.SteamOpenIdService;
import com.countatic.core.service.SteamProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Entrada e saída do usuário pela conta Steam.
 *
 * <p>Três endereços: {@code /login} manda para a Steam, {@code /login/retorno}
 * recebe a resposta e abre a sessão, {@code /sair} fecha. Mais o {@code /eu},
 * que leva quem está logado ao próprio painel sem precisar guardar a URL com
 * token.</p>
 *
 * <p><b>O que o login muda no produto:</b> hoje o acesso é por token secreto na
 * URL — funciona para uma pessoa, mas não responde "de quem é esta conta". Com
 * o login, o cadastro do código de autenticação passa a ser feito <b>por quem
 * é dono da conta</b>, e não por quem digita um SteamID no formulário.</p>
 */
@Slf4j
@Controller
public class SteamLoginController {

    private final SteamOpenIdService openId;
    private final SessaoService sessao;
    private final SteamProfileService perfis;
    private final PlayerRepository playerRepository;
    private final String urlBase;

    public SteamLoginController(SteamOpenIdService openId,
                                SessaoService sessao,
                                SteamProfileService perfis,
                                PlayerRepository playerRepository,
                                @Value("${countatic.web-base-url:http://localhost:8080}") String urlBase) {
        this.openId = openId;
        this.sessao = sessao;
        this.perfis = perfis;
        this.playerRepository = playerRepository;
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
    }

    /** Manda o navegador para a Steam. */
    @GetMapping("/login")
    public ResponseEntity<Void> entrar() {
        if (!sessao.estaConfigurado()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(openId.urlDeLogin(urlBase + "/login/retorno")))
                .build();
    }

    /**
     * Volta da Steam.
     *
     * <p>Os parâmetros que chegam aqui não provam nada sozinhos — quem prova é
     * a verificação de volta, dentro do {@link SteamOpenIdService}.</p>
     */
    @GetMapping("/login/retorno")
    @Transactional
    public ResponseEntity<Void> retorno(HttpServletRequest pedido, HttpServletResponse resposta) {
        Map<String, String> parametros = new HashMap<>();
        pedido.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) parametros.put(k, v[0]);
        });

        Optional<String> steamId = openId.verificar(parametros);
        if (steamId.isEmpty()) {
            return paraPagina("/?erro=login");
        }

        Player jogador = registrarOuAtualizar(steamId.get());
        sessao.abrir(resposta, jogador.getSteamId64());

        log.info("Login pela Steam: {} ({})", jogador.getDisplayName(), jogador.getSteamId64());
        return paraPagina("/eu");
    }

    /** Leva quem está logado ao próprio painel. */
    @GetMapping("/eu")
    public ResponseEntity<Void> eu(HttpServletRequest pedido) {
        Optional<Player> jogador = sessao.steamIdLogado(pedido)
                .flatMap(playerRepository::findBySteamId64);

        return jogador
                .map(p -> paraPagina("/p/" + p.getPublicToken()))
                .orElseGet(() -> paraPagina("/login"));
    }

    @GetMapping("/sair")
    public ResponseEntity<Void> sair(HttpServletResponse resposta) {
        sessao.fechar(resposta);
        return paraPagina("/");
    }

    // ═══════════════════════════════════════════════════════════════════

    /**
     * Cria o jogador na primeira entrada, ou atualiza nome e avatar nas demais.
     *
     * <p>Não toca no código de autenticação nem no share code: entrar é dizer
     * quem se é, e não recadastrar o que já estava configurado.</p>
     */
    private Player registrarOuAtualizar(String steamId64) {
        Player jogador = playerRepository.findBySteamId64(steamId64)
                .orElseGet(() -> Player.builder()
                        .steamId64(steamId64)
                        // Nome provisório: se a Web API responder logo abaixo,
                        // ele é trocado antes de salvar.
                        .displayName(steamId64)
                        .build());

        perfis.buscar(steamId64).ifPresent(p -> {
            jogador.setDisplayName(p.nome());
            if (p.avatarUrl() != null) jogador.setAvatarUrl(p.avatarUrl());
        });

        return playerRepository.save(jogador);
    }

    private ResponseEntity<Void> paraPagina(String caminho) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(caminho)).build();
    }
}
