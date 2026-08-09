package com.countatic.core.controller;

import com.countatic.core.dto.valve.ValveAuthRequestDTO;
import com.countatic.core.entity.Player;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.service.MatchDiscoveryScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayerAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PlayerRepository playerRepository;

    @MockBean
    private MatchDiscoveryScheduler discoveryScheduler;

    @Test
    @DisplayName("POST /api/players/auth — Deve registrar credenciais da Valve com sucesso")
    void shouldRegisterValveAuthSuccessfully() throws Exception {
        ValveAuthRequestDTO dto = ValveAuthRequestDTO.builder()
                .steamId64("76561198012345678")
                .authCode("AAAA-BBBB-CCCC")
                .initialShareCode("CSGO-7K4uX-B495P-LhnF7-66X8R-3S83G")
                .displayName("Fallen")
                .build();

        Player mockPlayer = Player.builder()
                .steamId64("76561198012345678")
                .displayName("Fallen")
                .authCode("AAAA-BBBB-CCCC")
                .latestShareCode("CSGO-7K4uX-B495P-LhnF7-66X8R-3S83G")
                .autoFetchEnabled(true)
                .build();

        when(playerRepository.findBySteamId64("76561198012345678")).thenReturn(Optional.empty());
        when(playerRepository.save(any(Player.class))).thenReturn(mockPlayer);

        mockMvc.perform(post("/api/players/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.steamId64").value("76561198012345678"))
                .andExpect(jsonPath("$.autoFetchEnabled").value(true));
    }

    @Test
    @DisplayName("POST /api/players/auth — Deve rejeitar SteamID64 com tamanho inválido")
    void shouldRejectInvalidSteamId() throws Exception {
        ValveAuthRequestDTO dto = ValveAuthRequestDTO.builder()
                .steamId64("123") // Menos de 17 dígitos
                .authCode("AAAA-BBBB-CCCC")
                .initialShareCode("CSGO-7K4uX-B495P-LhnF7-66X8R-3S83G")
                .build();

        mockMvc.perform(post("/api/players/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }
}
