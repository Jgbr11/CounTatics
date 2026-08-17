package com.countatic.core.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de {@code GET /api/players/{steamId}/trend}.
 *
 * <p>Sobe o contexto do MVC porque o que se testa aqui é o contrato — código
 * de status, forma do corpo e defaults de parâmetro —, e nada disso passa por
 * uma chamada direta ao método do controller.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrendEndpointTest {

    private static final String STEAM_ID = "76561199110265389";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Série vazia e métrica inválida precisam ser distinguíveis.
     *
     * <p>Devolver 200 com lista vazia para uma métrica que não existe mandaria
     * quem integra procurar o problema no histórico do jogador, não no nome do
     * parâmetro.</p>
     */
    @Test
    @DisplayName("métrica desconhecida devolve 400 com a lista das válidas")
    void metricaDesconhecidaDevolve400() throws Exception {
        mockMvc.perform(get("/api/players/{id}/trend", STEAM_ID).param("metric", "naoExiste"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.metricasValidas").isNotEmpty());
    }

    @Test
    @DisplayName("jogador sem histórico devolve 200 com série vazia")
    void semHistoricoDevolve200() throws Exception {
        mockMvc.perform(get("/api/players/{id}/trend", "76561198999999999").param("metric", "adr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("adr"))
                .andExpect(jsonPath("$.label").value("ADR"))
                .andExpect(jsonPath("$.maiorEhMelhor").value(true))
                .andExpect(jsonPath("$.pontos").isArray());
    }

    @Test
    @DisplayName("sem o parâmetro metric, o default é ADR")
    void metricaTemDefault() throws Exception {
        mockMvc.perform(get("/api/players/{id}/trend", STEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("adr"));
    }

    /** A direção acompanha a série porque quem desenha precisa saber para que lado subir é bom. */
    @Test
    @DisplayName("deathsPerRound viaja com maiorEhMelhor = false")
    void direcaoInvertidaChegaAoCliente() throws Exception {
        mockMvc.perform(get("/api/players/{id}/trend", STEAM_ID).param("metric", "deathsPerRound"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maiorEhMelhor").value(false));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Múltiplas séries — alimenta as sparklines dos cards
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/trends devolve uma série por métrica pedida, na ordem pedida")
    void trendsDevolveUmaSeriePorMetrica() throws Exception {
        mockMvc.perform(get("/api/players/{id}/trends", STEAM_ID)
                        .param("metrics", "adr,kdRatio,headshotPercentage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(3))
                .andExpect(jsonPath("$.series[0].metric").value("adr"))
                .andExpect(jsonPath("$.series[1].metric").value("kdRatio"))
                .andExpect(jsonPath("$.series[2].metric").value("headshotPercentage"));
    }

    /**
     * Validar tudo antes de consultar importa: devolver metade das séries e
     * estourar no meio deixaria o cliente sem saber o que veio.
     */
    @Test
    @DisplayName("uma métrica inválida no meio invalida a requisição inteira")
    void umaMetricaInvalidaRejeitaTudo() throws Exception {
        mockMvc.perform(get("/api/players/{id}/trends", STEAM_ID)
                        .param("metrics", "adr,naoExiste,kdRatio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.metricasValidas").isNotEmpty());
    }
}
