package com.countatic.core.service;

import com.countatic.core.dto.stats.PlayerDashboardDTO;
import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import com.countatic.core.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do painel consolidado.
 *
 * <p>O ponto sensível aqui é a <b>média</b>: métrica ausente não pode entrar
 * como zero, e resultado desconhecido não pode ser somado a vitórias nem a
 * derrotas. Nos dois casos o erro produz um número plausível — que é
 * exatamente o tipo de defeito que passa despercebido sem teste.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PlayerDashboardServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    @Autowired
    private PlayerDashboardService dashboardService;

    @Autowired
    private PlayerMatchStatsRepository statsRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRepository playerRepository;

    private Player jogador;

    @BeforeEach
    void preparar() {
        statsRepository.deleteAll();
        matchRepository.deleteAll();
        playerRepository.deleteAll();

        jogador = playerRepository.save(Player.builder()
                .steamId64(STEAM_ID).displayName("JGBR11").build());
    }

    @Test
    @DisplayName("o token público é gerado no cadastro e abre o painel")
    void tokenAbreOPainel() {
        assertThat(jogador.getPublicToken()).isNotBlank();

        assertThat(dashboardService.porToken(jogador.getPublicToken(), 20)).isPresent();
        assertThat(dashboardService.porToken("token-que-nao-existe", 20)).isEmpty();
    }

    /**
     * Se o ausente contasse como zero, a média de 80 e 100 com um nulo no meio
     * cairia para 60 — e o jogador leria uma queda que não houve.
     */
    @Test
    @DisplayName("métrica ausente fica fora da média em vez de virar zero")
    void ausenteNaoEntraNaMedia() {
        Instant agora = Instant.now();
        criar(agora.minus(2, ChronoUnit.DAYS), 80.0, null, null);
        criar(agora.minus(1, ChronoUnit.DAYS), null, null, null);
        criar(agora, 100.0, null, null);

        PlayerDashboardDTO d = dashboardService.porSteamId(STEAM_ID, 20).orElseThrow();

        assertThat(d.getMedias().get("adr")).isEqualTo(90.0);
        assertThat(d.getPartidasAnalisadas()).isEqualTo(3);
    }

    /**
     * Contar o desconhecido como derrota daria um retrospecto falso — e é o
     * caso de toda partida analisada antes de o resultado passar a ser guardado.
     */
    @Test
    @DisplayName("resultado desconhecido é contado à parte, não como derrota")
    void desconhecidoNaoViraDerrota() {
        Instant agora = Instant.now();
        criar(agora.minus(3, ChronoUnit.DAYS), 90.0, Team.CT, true);
        criar(agora.minus(2, ChronoUnit.DAYS), 90.0, Team.TR, false);
        criar(agora.minus(1, ChronoUnit.DAYS), 90.0, null, null);

        PlayerDashboardDTO d = dashboardService.porSteamId(STEAM_ID, 20).orElseThrow();

        assertThat(d.getVitorias()).isEqualTo(1);
        assertThat(d.getDerrotas()).isEqualTo(1);
        assertThat(d.getResultadoDesconhecido()).isEqualTo(1);
    }

    @Test
    @DisplayName("as partidas saem da mais recente para a mais antiga, com link")
    void partidasSaemDaMaisRecente() {
        Instant agora = Instant.now();
        criar(agora.minus(2, ChronoUnit.DAYS), 10.0, null, null);
        criar(agora, 30.0, null, null);
        criar(agora.minus(1, ChronoUnit.DAYS), 20.0, null, null);

        PlayerDashboardDTO d = dashboardService.porSteamId(STEAM_ID, 20).orElseThrow();

        assertThat(d.getPartidas()).extracting(PlayerDashboardDTO.PartidaResumo::getAdr)
                .containsExactly(30.0, 20.0, 10.0);
        assertThat(d.getPartidas().get(0).getPublicToken()).isNotBlank();
    }

    @Test
    @DisplayName("o placar da lista vem orientado pelo lado do jogador")
    void placarOrientado() {
        criar(Instant.now(), 90.0, Team.TR, false);

        PlayerDashboardDTO.PartidaResumo p =
                dashboardService.porSteamId(STEAM_ID, 20).orElseThrow().getPartidas().get(0);

        // A partida é 13 (CT) x 8 (TR); quem jogou de TR perdeu por 8-13.
        assertThat(p.getScoreSelf()).isEqualTo(8);
        assertThat(p.getScoreEnemy()).isEqualTo(13);
    }

    @Test
    @DisplayName("jogador sem partidas devolve painel vazio, não erro")
    void semPartidasDevolvePainelVazio() {
        PlayerDashboardDTO d = dashboardService.porSteamId(STEAM_ID, 20).orElseThrow();

        assertThat(d.getPartidasAnalisadas()).isZero();
        assertThat(d.getMedias()).isEmpty();
        assertThat(d.getPartidas()).isEmpty();
        assertThat(d.getPlayerName()).isEqualTo("JGBR11");
    }

    /** Sem teto, um parâmetro grande varreria o histórico inteiro com um join por linha. */
    @Test
    @DisplayName("a janela de partidas é limitada")
    void janelaEhLimitada() {
        Instant agora = Instant.now();
        for (int i = 0; i < 5; i++) {
            criar(agora.minus(i, ChronoUnit.DAYS), 50.0, null, null);
        }

        assertThat(dashboardService.porSteamId(STEAM_ID, 2).orElseThrow()
                .getPartidasAnalisadas()).isEqualTo(2);
        assertThat(dashboardService.porSteamId(STEAM_ID, 100_000).orElseThrow()
                .getPartidasAnalisadas()).isEqualTo(5);
    }

    // ═══════════════════════════════════════════════════════════════

    private void criar(Instant jogadaEm, Double adr, Team lado, Boolean venceu) {
        Match m = matchRepository.save(Match.builder()
                .demoFileHash("hash-" + jogadaEm.toEpochMilli())
                .demoFileName("t.dem")
                .mapName("de_mirage")
                .durationSeconds(1800)
                .scoreCT(13).scoreTR(8)
                .totalRounds(21).tickRate(64)
                .status(MatchStatus.COMPLETED)
                .playedAt(jogadaEm)
                .build());

        statsRepository.save(PlayerMatchStats.builder()
                .match(m)
                .player(jogador)
                .steamId64(STEAM_ID)
                .roundsPlayed(21)
                .adr(adr)
                .kdRatio(1.1)
                .kills(20).deaths(18)
                .playerSide(lado)
                .won(venceu)
                .build());
    }
}
