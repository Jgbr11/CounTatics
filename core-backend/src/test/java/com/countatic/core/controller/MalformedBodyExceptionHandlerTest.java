package com.countatic.core.controller;

import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.service.MatchDiscoveryScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sobe o contexto de verdade porque o advice só existe no pipeline do MVC:
 * chamar o controller direto, como faz {@link GsiControllerTest}, nem sequer
 * passa pela desserialização que estoura a exceção — não provaria nada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MalformedBodyExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlayerRepository playerRepository;

    @MockBean
    private MatchDiscoveryScheduler discoveryScheduler;

    @Test
    @DisplayName("JSON inválido em /api/gsi devolve 200 — 400 faria o CS2 abandonar o endpoint")
    void jsonInvalidoNoGsiDevolve200() throws Exception {
        mockMvc.perform(post("/api/gsi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ isso não é json "))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Corpo vazio em /api/gsi devolve 200")
    void corpoVazioNoGsiDevolve200() throws Exception {
        mockMvc.perform(post("/api/gsi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("JSON inválido nos demais endpoints continua 400 — mascarar corpo inválido na "
            + "API inteira trocaria um bug por outro")
    void jsonInvalidoForaDoGsiPreserva400() throws Exception {
        mockMvc.perform(post("/api/players/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ isso não é json "))
                .andExpect(status().isBadRequest());
    }
}
