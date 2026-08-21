package com.countatic.core.controller;

import com.countatic.core.entity.Player;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.service.SessaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes das rotas de entrada e saída.
 *
 * <p>Não chegam a falar com a Steam — o que se verifica aqui é o contorno: para
 * onde o navegador é mandado, e o que acontece com quem não está logado.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "countatic.session-secret=segredo-de-teste-suficientemente-longo",
        "countatic.web-base-url=http://localhost:8080"
})
class SteamLoginControllerTest {

    private static final String STEAM_ID = "76561199110265389";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessaoService sessao;

    @Autowired
    private PlayerRepository playerRepository;

    @BeforeEach
    void preparar() {
        playerRepository.findBySteamId64(STEAM_ID).ifPresent(playerRepository::delete);
    }

    @Test
    @DisplayName("/login manda o navegador para a Steam")
    void loginRedirecionaParaSteam() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://steamcommunity.com/openid/login?")))
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString(
                                "return_to=http%3A%2F%2Flocalhost%3A8080%2Flogin%2Fretorno")));
    }

    @Test
    @DisplayName("/eu sem sessão manda para o login")
    void euSemSessaoVaiParaLogin() throws Exception {
        mockMvc.perform(get("/eu"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("/eu com sessão leva ao painel do próprio jogador")
    void euComSessaoVaiParaOPainel() throws Exception {
        Player jogador = playerRepository.save(Player.builder()
                .steamId64(STEAM_ID)
                .displayName("JGBR11")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        MockHttpServletResponse r = new MockHttpServletResponse();
        sessao.abrir(r, STEAM_ID);

        mockMvc.perform(get("/eu").cookie(r.getCookie(SessaoService.COOKIE)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/p/" + jogador.getPublicToken()));
    }

    /**
     * Um retorno forjado direto no navegador, sem passar pela Steam. Tem de
     * cair fora — é o ataque mais óbvio contra um login OpenID.
     */
    @Test
    @DisplayName("retorno forjado não abre sessão")
    void retornoForjadoNaoLoga() throws Exception {
        mockMvc.perform(get("/login/retorno")
                        .param("openid.mode", "id_res")
                        .param("openid.claimed_id",
                                "https://steamcommunity.com/openid/id/" + STEAM_ID)
                        .param("openid.sig", "invento-qualquer"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/?erro=login"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("/sair apaga o cookie")
    void sairApagaCookie() throws Exception {
        mockMvc.perform(get("/sair"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
}
