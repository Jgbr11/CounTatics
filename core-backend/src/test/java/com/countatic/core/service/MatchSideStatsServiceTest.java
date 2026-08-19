package com.countatic.core.service;

import com.countatic.core.dto.stats.SideStatsDTO;
import com.countatic.core.entity.*;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do recorte por lado.
 *
 * <p>O erro mais provável aqui não estoura: ele produz <b>número plausível</b>.
 * Se o {@code totalRounds} da visão continuasse sendo o da partida inteira,
 * toda métrica por round de cada lado sairia pela metade — e nada quebraria.
 * Por isso os testes olham para o denominador, não só para a existência da
 * resposta.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MatchSideStatsServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    @Autowired
    private MatchSideStatsService service;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRepository playerRepository;

    private Player eu;
    private Player inimigo;

    @BeforeEach
    void preparar() {
        matchRepository.deleteAll();
        playerRepository.deleteAll();

        eu = playerRepository.save(Player.builder()
                .steamId64(STEAM_ID).displayName("JGBR11").build());
        inimigo = playerRepository.save(Player.builder()
                .steamId64("76561198000000002").displayName("INIMIGO").build());
    }

    /**
     * Quatro rounds: dois de CT, dois de TR. As kills estão todas de um lado
     * só, então a separação tem que refletir isso.
     */
    @Test
    @DisplayName("cada lado recebe apenas os rounds em que o jogador atuou nele")
    void separaRoundsPorLado() {
        Match m = partidaComDoisLados();

        SideStatsDTO d = service.calcular(m.getId(), STEAM_ID).orElseThrow();

        assertThat(d.getCt().getRoundsJogados()).isEqualTo(2);
        assertThat(d.getTr().getRoundsJogados()).isEqualTo(2);
    }

    /**
     * O ponto central do desenho: por round significa por round DAQUELE lado.
     *
     * <p>Duas kills em dois rounds de CT são 1,0 kill/round de CT. Se o
     * denominador fosse o total da partida (4), sairia 0,5 — metade do valor,
     * sem nenhum sinal de erro.</p>
     */
    @Test
    @DisplayName("métricas por round usam o total de rounds DO LADO, não da partida")
    void denominadorEhDoLado() {
        Match m = partidaComDoisLados();

        SideStatsDTO d = service.calcular(m.getId(), STEAM_ID).orElseThrow();

        Double kprCt = d.getCt().getMetrics().get("Aim").get("killsPerRound");
        assertThat(kprCt).isEqualTo(1.0);

        // Do lado TR o jogador só morreu, então 0 kills em 2 rounds.
        assertThat(d.getTr().getMetrics().get("Aim").get("killsPerRound")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("o recorte não altera as entidades originais")
    void recorteNaoMutaOriginal() {
        Match m = partidaComDoisLados();
        int roundsAntes = m.getRounds().size();

        service.calcular(m.getId(), STEAM_ID).orElseThrow();

        // A visão usa o builder justamente para não reapontar match/round das
        // entidades reais. Se usasse addRound/addEvent, os rounds da partida
        // original passariam a apontar para a visão.
        Match recarregada = matchRepository.findByIdWithRounds(m.getId()).orElseThrow();
        assertThat(recarregada.getRounds()).hasSize(roundsAntes);
        assertThat(recarregada.getTotalRounds()).isEqualTo(4);
        recarregada.getRounds().forEach(r ->
                assertThat(r.getMatch().getId()).isEqualTo(m.getId()));
    }

    @Test
    @DisplayName("jogador que atuou de um lado só devolve o outro vazio, sem erro")
    void ladoSemRoundsVemVazio() {
        Match m = matchRepository.save(base());
        Round r = round(1, 0);
        r.addEvent(kill(eu, inimigo, Team.CT, Team.TR, 100));
        m.addRound(r);
        m.setTotalRounds(1);
        matchRepository.save(m);

        SideStatsDTO d = service.calcular(m.getId(), STEAM_ID).orElseThrow();

        assertThat(d.getCt().getRoundsJogados()).isEqualTo(1);
        assertThat(d.getTr().getRoundsJogados()).isZero();
        assertThat(d.getTr().getMetrics()).isEmpty();
    }

    @Test
    @DisplayName("partida ou jogador inexistente devolve vazio")
    void inexistenteDevolveVazio() {
        Match m = matchRepository.save(base());
        assertThat(service.calcular(999_999L, STEAM_ID)).isEmpty();
        assertThat(service.calcular(m.getId(), "76561198999999999")).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════

    /** 2 rounds de CT (jogador mata) e 2 de TR (jogador morre). */
    private Match partidaComDoisLados() {
        Match m = matchRepository.save(base());

        for (int i = 1; i <= 2; i++) {
            Round r = round(i, i * 1000);
            r.addEvent(kill(eu, inimigo, Team.CT, Team.TR, i * 1000 + 100));
            m.addRound(r);
        }
        for (int i = 3; i <= 4; i++) {
            Round r = round(i, i * 1000);
            r.addEvent(kill(inimigo, eu, Team.CT, Team.TR, i * 1000 + 100));
            m.addRound(r);
        }
        m.setTotalRounds(4);
        return matchRepository.save(m);
    }

    private static Match base() {
        return Match.builder()
                .demoFileHash("hash-lados").demoFileName("t.dem").mapName("de_mirage")
                .durationSeconds(1800).scoreCT(13).scoreTR(8)
                .totalRounds(0).tickRate(64)
                .status(MatchStatus.COMPLETED).playedAt(Instant.now())
                .build();
    }

    /**
     * Round completo o bastante para persistir.
     *
     * <p>A entidade exige vencedor, motivo do fim, ticks, duração e placar
     * acumulado. Nenhum deles importa para o recorte por lado — estão aqui só
     * porque, sem eles, a falha aconteceria na gravação do fixture e não no
     * código sob teste.</p>
     */
    private static Round round(int numero, int startTick) {
        return Round.builder()
                .roundNumber(numero)
                .startTick(startTick)
                .endTick(startTick + 3000)
                .durationSeconds(46.0)
                .winnerSide(Team.CT)
                .endReason(RoundEndReason.CT_WIN_ELIMINATION)
                .ctScoreAfter(0)
                .trScoreAfter(0)
                .build();
    }

    private static MatchEvent kill(Player ator, Player vitima,
                                   Team ladoAtor, Team ladoVitima, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.KILL).actor(ator).victim(vitima).tick(tick)
                .actorSide(ladoAtor).victimSide(ladoVitima)
                .actorPositionX(0.0).actorPositionY(0.0).actorPositionZ(0.0)
                .victimPositionX(500.0).victimPositionY(0.0).victimPositionZ(0.0)
                .build();
    }
}
