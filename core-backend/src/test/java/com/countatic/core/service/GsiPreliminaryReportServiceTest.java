package com.countatic.core.service;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cobre o conteúdo do relatório preliminar, que saiu de {@link GsiEventService}
 * para cá quando o {@code @Async} ganhou uma borda de bean de verdade.
 */
class GsiPreliminaryReportServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    private SteamBotClientService botClient;
    private GsiPreliminaryReportService service;

    @BeforeEach
    void setUp() {
        botClient = mock(SteamBotClientService.class);
        service = new GsiPreliminaryReportService(botClient);
    }

    @Test
    @DisplayName("Monta a mensagem com mapa, placar e as stats do próprio jogador")
    void enviaComStatsDoJogador() {
        service.enviar(STEAM_ID, payload(), 13, 9);

        ArgumentCaptor<String> mensagem = ArgumentCaptor.forClass(String.class);
        verify(botClient).sendSimpleNotification(eq(STEAM_ID), mensagem.capture());

        assertThat(mensagem.getValue())
                .contains("de_mirage")
                .contains("13-9")
                .contains("22/6/15")
                .contains("4 MVPs")
                .contains("Score 52");
    }

    @Test
    @DisplayName("Vitória, derrota e empate escolhem o emoji do resultado")
    void marcaOResultado() {
        service.enviar(STEAM_ID, payload(), 13, 9);
        service.enviar(STEAM_ID, payload(), 9, 13);
        service.enviar(STEAM_ID, payload(), 12, 12);

        ArgumentCaptor<String> mensagens = ArgumentCaptor.forClass(String.class);
        verify(botClient, times(3)).sendSimpleNotification(eq(STEAM_ID), mensagens.capture());

        assertThat(mensagens.getAllValues().get(0)).contains("13-9 ✅");
        assertThat(mensagens.getAllValues().get(1)).contains("9-13 ❌");
        assertThat(mensagens.getAllValues().get(2)).contains("12-12 🤝");
    }

    @Test
    @DisplayName("Sem matchStats não há relatório a enviar")
    void naoEnviaSemMatchStats() {
        GsiPayloadDTO semStats = payload();
        semStats.getPlayer().setMatchStats(null);

        service.enviar(STEAM_ID, semStats, 13, 9);

        verify(botClient, never()).sendSimpleNotification(any(), any());
    }

    @Test
    @DisplayName("Falha no bot é engolida — perder o preliminar não pode derrubar o gatilho, "
            + "e o job já está enfileirado")
    void engoleFalhaDoBot() {
        when(botClient.sendSimpleNotification(any(), any()))
                .thenThrow(new RuntimeException("Steam aplicou rate limit"));

        assertThatCode(() -> service.enviar(STEAM_ID, payload(), 13, 9))
                .doesNotThrowAnyException();
    }

    // ─── Fábrica de payload ───────────────────────────────────────────

    private GsiPayloadDTO payload() {
        GsiPayloadDTO p = new GsiPayloadDTO();

        GsiPayloadDTO.MapState mapa = new GsiPayloadDTO.MapState();
        mapa.setName("de_mirage");
        p.setMap(mapa);

        GsiPayloadDTO.PlayerState jogador = new GsiPayloadDTO.PlayerState();
        jogador.setSteamid(STEAM_ID);
        jogador.setTeam("CT");

        GsiPayloadDTO.MatchStats stats = new GsiPayloadDTO.MatchStats();
        stats.setKills(22);
        stats.setAssists(6);
        stats.setDeaths(15);
        stats.setMvps(4);
        stats.setScore(52);
        jogador.setMatchStats(stats);

        p.setPlayer(jogador);
        return p;
    }
}
