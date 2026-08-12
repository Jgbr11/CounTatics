package com.countatic.core.service;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.entity.Player;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.PlayerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GsiEventServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    private MatchFetchJobService jobService;
    private MatchFetchJobRepository jobRepository;
    private PlayerRepository playerRepository;
    private SteamBotClientService botClient;
    private GsiEventService service;

    @BeforeEach
    void setUp() {
        jobService = mock(MatchFetchJobService.class);
        jobRepository = mock(MatchFetchJobRepository.class);
        playerRepository = mock(PlayerRepository.class);
        botClient = mock(SteamBotClientService.class);

        service = new GsiEventService(jobService, jobRepository, playerRepository,
                botClient, new ObjectMapper());

        Player jogador = Player.builder()
                .id(1L).steamId64(STEAM_ID).displayName("JGBR11")
                .autoFetchEnabled(true)
                .build();
        when(playerRepository.findBySteamId64(STEAM_ID)).thenReturn(Optional.of(jogador));
        when(jobRepository.existsBySteamId64AndStatusIn(eq(STEAM_ID), anyList())).thenReturn(false);
        when(jobService.enqueueAwaitingShareCode(any(), any(), any(), any(), any()))
                .thenReturn(MatchFetchJob.builder().id(1L).steamId64(STEAM_ID).build());
    }

    @Test
    @DisplayName("Enfileira uma única vez na transição live → gameover")
    void enfileiraNaBorda() {
        service.processar(payload("live", "CT", 13, 9));
        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());

        service.processar(payload("gameover", "CT", 13, 9));
        verify(jobService, times(1))
                .enqueueAwaitingShareCode(eq(STEAM_ID), eq("de_mirage"), eq(13), eq(9), anyString());
    }

    @Test
    @DisplayName("Ignora o gameover repetido — o CS2 reenvia o payload a cada 2 s")
    void ignoraGameoverRepetido() {
        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, times(1)).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Orienta o placar pelo lado do jogador: como TR, o próprio placar é o dos T")
    void orientaPlacarPeloLadoDoJogador() {
        service.processar(payload("live", "T", 13, 9));
        service.processar(payload("gameover", "T", 13, 9));

        // team_ct = 13, team_t = 9; jogando de T, o placar próprio é 9 contra 13.
        verify(jobService).enqueueAwaitingShareCode(eq(STEAM_ID), eq("de_mirage"),
                eq(9), eq(13), anyString());
    }

    @Test
    @DisplayName("Não enfileira quando já há sondagem aberta — guarda que sobrevive a restart")
    void naoEnfileiraComSondagemAberta() {
        when(jobRepository.existsBySteamId64AndStatusIn(
                STEAM_ID, List.of(MatchFetchStatus.AWAITING_SHARECODE))).thenReturn(true);

        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Ignora jogador não cadastrado")
    void ignoraJogadorDesconhecido() {
        when(playerRepository.findBySteamId64(STEAM_ID)).thenReturn(Optional.empty());

        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Não age no primeiro payload já em gameover — não há borda que prove que a partida acabou agora")
    void naoAgeSemFaseAnterior() {
        // O CS2 pode ser aberto com uma partida encerrada na tela. Sem a fase
        // anterior, agir seria adivinhar; o polling de 5 min cobre esse caso.
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Envia o relatório preliminar com as stats do próprio jogador")
    void enviaRelatorioPreliminar() {
        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(botClient).sendSimpleNotification(eq(STEAM_ID), contains("de_mirage"));
    }

    @Test
    @DisplayName("Não quebra nem enfileira quando o payload chega sem fase (map.phase nulo)")
    void naoQuebraComFaseNula() {
        GsiPayloadDTO semFase = payload("live", "CT", 13, 9);
        semFase.getMap().setPhase(null);

        service.processar(semFase);
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Não enfileira nem notifica quando o jogador tem auto-fetch desativado")
    void naoEnfileiraComAutoFetchDesativado() {
        Player jogadorSemAutoFetch = Player.builder()
                .id(1L).steamId64(STEAM_ID).displayName("JGBR11")
                .autoFetchEnabled(false)
                .build();
        when(playerRepository.findBySteamId64(STEAM_ID)).thenReturn(Optional.of(jogadorSemAutoFetch));

        service.processar(payload("live", "CT", 13, 9));
        service.processar(payload("gameover", "CT", 13, 9));

        verify(jobService, never()).enqueueAwaitingShareCode(any(), any(), any(), any(), any());
        verify(botClient, never()).sendSimpleNotification(any(), any());
    }

    // ─── Fábrica de payload ───────────────────────────────────────────

    private GsiPayloadDTO payload(String fase, String time, int placarCt, int placarT) {
        GsiPayloadDTO p = new GsiPayloadDTO();

        GsiPayloadDTO.Provider provider = new GsiPayloadDTO.Provider();
        provider.setSteamid(STEAM_ID);
        p.setProvider(provider);

        GsiPayloadDTO.MapState mapa = new GsiPayloadDTO.MapState();
        mapa.setName("de_mirage");
        mapa.setPhase(fase);
        mapa.setMode("premier");

        GsiPayloadDTO.TeamState ct = new GsiPayloadDTO.TeamState();
        ct.setScore(placarCt);
        mapa.setTeamCt(ct);

        GsiPayloadDTO.TeamState t = new GsiPayloadDTO.TeamState();
        t.setScore(placarT);
        mapa.setTeamT(t);

        p.setMap(mapa);

        GsiPayloadDTO.PlayerState jogador = new GsiPayloadDTO.PlayerState();
        jogador.setSteamid(STEAM_ID);
        jogador.setName("JGBR11");
        jogador.setTeam(time);

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
