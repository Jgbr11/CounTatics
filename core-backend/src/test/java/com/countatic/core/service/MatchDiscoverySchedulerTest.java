package com.countatic.core.service;

import com.countatic.core.entity.Player;
import com.countatic.core.exception.ValveAuthException;
import com.countatic.core.exception.ValveTransientException;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Testes da descoberta de partidas.
 *
 * O foco é a <b>ordem</b> das operações: o job precisa estar durável antes de o
 * ponteiro do jogador avançar. Inverter isso foi o que fez uma partida real ser
 * perdida em definitivo durante a implementação.
 */
@SpringBootTest
@ActiveProfiles("test")
class MatchDiscoverySchedulerTest {

    private static final String STEAM_ID = "76561199110265389";
    private static final String CODIGO_ANTIGO = "CSGO-otTxQ-qUuHJ-i97ru-8Qu73-9L9PL";
    private static final String CODIGO_NOVO = "CSGO-Pf6Xz-AXpEG-GdzKr-GyWv6-pdueM";

    @Autowired
    private MatchDiscoveryScheduler scheduler;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchFetchJobRepository jobRepository;

    @MockBean
    private ValveApiService valveApiService;

    @MockBean
    private SteamBotClientService steamBotClientService;

    private Player jogador;

    @BeforeEach
    void preparar() {
        jobRepository.deleteAll();
        playerRepository.deleteAll();

        jogador = playerRepository.save(Player.builder()
                .steamId64(STEAM_ID)
                .displayName("JGBR11")
                .authCode("8TD6-UWWHY-F7MJ")
                .latestShareCode(CODIGO_ANTIGO)
                .autoFetchEnabled(true)
                .build());
    }

    @Test
    @DisplayName("partida nova gera job E avança o ponteiro do jogador")
    void descobreEEnfileira() {
        when(valveApiService.fetchNextMatchShareCode(STEAM_ID, jogador.getAuthCode(), CODIGO_ANTIGO))
                .thenReturn(CODIGO_NOVO);

        boolean achou = scheduler.discoverForPlayer(jogador);

        assertTrue(achou);
        assertEquals(1, jobRepository.count(), "a partida precisa ficar enfileirada");
        assertEquals(CODIGO_NOVO, jobRepository.findAll().get(0).getShareCode());
        assertEquals(CODIGO_NOVO,
                playerRepository.findBySteamId64(STEAM_ID).orElseThrow().getLatestShareCode(),
                "o ponteiro avança para não travar a lista encadeada da Valve");
    }

    /**
     * O ponteiro avança de propósito mesmo com falha adiante:
     * {@code GetNextMatchSharingCode} é uma lista encadeada por {@code knowncode},
     * e não avançar impediria descobrir a partida N+2 para sempre.
     * A segurança vem do job durável, não de segurar o ponteiro.
     */
    @Test
    @DisplayName("o job é persistido, então a partida sobrevive a falhas posteriores")
    void jobSobreviveAFalhaPosterior() {
        when(valveApiService.fetchNextMatchShareCode(anyString(), anyString(), anyString()))
                .thenReturn(CODIGO_NOVO);

        scheduler.discoverForPlayer(jogador);

        // Mesmo que tudo depois disso quebre, o registro está no banco e o
        // worker vai retomá-lo.
        assertEquals(1, jobRepository.count());
        assertNotNull(jobRepository.findAll().get(0).getId(),
                "o job precisa estar commitado, não apenas em memória");
    }

    @Test
    @DisplayName("sem partida nova, nada é enfileirado e o ponteiro não muda")
    void semPartidaNova() {
        when(valveApiService.fetchNextMatchShareCode(anyString(), anyString(), anyString()))
                .thenReturn(null);

        assertFalse(scheduler.discoverForPlayer(jogador));
        assertEquals(0, jobRepository.count());
        assertEquals(CODIGO_ANTIGO,
                playerRepository.findBySteamId64(STEAM_ID).orElseThrow().getLatestShareCode());
    }

    @Test
    @DisplayName("o mesmo share code devolvido não conta como partida nova")
    void mesmoCodigoNaoEhNovidade() {
        when(valveApiService.fetchNextMatchShareCode(anyString(), anyString(), anyString()))
                .thenReturn(CODIGO_ANTIGO);

        assertFalse(scheduler.discoverForPlayer(jogador));
        assertEquals(0, jobRepository.count());
    }

    /**
     * Credencial inválida é falha PERMANENTE. Antes, o erro 403 se repetia a
     * cada 5 minutos indefinidamente, sem que nada no sistema reagisse.
     */
    @Test
    @DisplayName("credencial inválida desabilita o auto-fetch e avisa o jogador")
    void credencialInvalidaDesabilitaJogador() {
        when(valveApiService.fetchNextMatchShareCode(anyString(), anyString(), anyString()))
                .thenThrow(new ValveAuthException(STEAM_ID, "HTTP 403"));

        assertFalse(scheduler.discoverForPlayer(jogador));

        Player recarregado = playerRepository.findBySteamId64(STEAM_ID).orElseThrow();
        assertFalse(recarregado.getAutoFetchEnabled(),
                "o jogador precisa sair do ciclo, senão o 403 se repete para sempre");

        verify(steamBotClientService).sendSimpleNotification(eq(STEAM_ID), contains("Authentication Code"));
    }

    @Test
    @DisplayName("falha temporária NÃO desabilita o jogador")
    void falhaTemporariaMantemJogadorAtivo() {
        when(valveApiService.fetchNextMatchShareCode(anyString(), anyString(), anyString()))
                .thenThrow(new ValveTransientException("API da Valve fora do ar"));

        assertFalse(scheduler.discoverForPlayer(jogador));

        assertTrue(playerRepository.findBySteamId64(STEAM_ID).orElseThrow().getAutoFetchEnabled(),
                "erro transitório não pode custar o monitoramento do jogador");
        assertEquals(0, jobRepository.count());
    }

    @Test
    @DisplayName("descobrir duas vezes a mesma partida não duplica o job")
    void descobertaRepetidaNaoDuplica() {
        when(valveApiService.fetchNextMatchShareCode(STEAM_ID, jogador.getAuthCode(), CODIGO_ANTIGO))
                .thenReturn(CODIGO_NOVO);
        scheduler.discoverForPlayer(jogador);

        // Simula o ponteiro sendo rebobinado (ou uma corrida com o GSI).
        Player p = playerRepository.findBySteamId64(STEAM_ID).orElseThrow();
        p.setLatestShareCode(CODIGO_ANTIGO);
        playerRepository.save(p);

        scheduler.discoverForPlayer(p);

        assertEquals(1, jobRepository.count(), "a constraint única impede a duplicata");
    }

    @Test
    @DisplayName("lastPolledAt é atualizado a cada verificação")
    void registraUltimaVerificacao() {
        when(valveApiService.fetchNextMatchShareCode(anyString(), anyString(), anyString()))
                .thenReturn(null);

        scheduler.discoverForPlayer(jogador);

        assertNotNull(playerRepository.findBySteamId64(STEAM_ID).orElseThrow().getLastPolledAt());
    }
}
