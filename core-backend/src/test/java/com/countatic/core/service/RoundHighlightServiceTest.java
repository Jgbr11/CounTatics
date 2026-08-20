package com.countatic.core.service;

import com.countatic.core.dto.stats.RoundHighlightsDTO;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes dos destaques de rodada.
 *
 * <p>O que os testes cercam não é a aritmética — é o <b>julgamento</b>: um
 * clutch 1v3 precisa ganhar de um round com o mesmo número de kills sem
 * clutch, e uma partida morna precisa devolver lista vazia em vez de chamar
 * uma kill de destaque.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RoundHighlightServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    @Autowired
    private RoundHighlightService service;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private PlayerMatchStatsRepository statsRepository;

    private Player eu;
    private Player aliado;
    private Player inimigo;
    private Match partida;

    @BeforeEach
    void preparar() {
        // Os desempenhos saem primeiro: têm chave estrangeira para as partidas
        // e outras classes de teste deixam linhas para trás.
        statsRepository.deleteAll();
        matchRepository.deleteAll();
        playerRepository.deleteAll();

        eu = playerRepository.save(Player.builder()
                .steamId64(STEAM_ID).displayName("JGBR11").build());
        aliado = playerRepository.save(Player.builder()
                .steamId64("76561198000000003").displayName("ALIADO").build());
        inimigo = playerRepository.save(Player.builder()
                .steamId64("76561198000000002").displayName("INIMIGO").build());

        partida = Match.builder()
                .demoFileHash("hash-destaques").demoFileName("t.dem").mapName("de_mirage")
                .durationSeconds(1800).scoreCT(13).scoreTR(8)
                .totalRounds(21).tickRate(64)
                .status(MatchStatus.COMPLETED).playedAt(Instant.now())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("o clutch 1v3 ganha do round com as mesmas kills sem clutch")
    void clutchGanhaDoRoundComuns() {
        // Round 1: três kills limpas, round vencido, sem clutch.
        Round r1 = round(1, Team.CT);
        r1.addEvent(kill(eu, inimigo, 100));
        r1.addEvent(kill(eu, inimigo, 200));
        r1.addEvent(kill(eu, inimigo, 300));

        // Round 2: as mesmas três kills, mas com o time inteiro no chão e três
        // inimigos em pé — 1v3.
        Round r2 = round(2, Team.CT);
        r2.addEvent(kill(aliado, Team.CT, inimigo, 20));
        r2.addEvent(kill(aliado, Team.CT, inimigo, 30));
        r2.addEvent(kill(inimigo, Team.TR, aliado, 50));
        r2.addEvent(kill(inimigo, Team.TR, aliado, 60));
        r2.addEvent(kill(inimigo, Team.TR, aliado, 70));
        r2.addEvent(kill(inimigo, Team.TR, aliado, 80));
        r2.addEvent(kill(eu, inimigo, 100));
        r2.addEvent(kill(eu, inimigo, 200));
        r2.addEvent(kill(eu, inimigo, 300));

        List<RoundHighlightsDTO.Destaque> d = calcular();

        assertThat(d.get(0).getRoundNumber()).isEqualTo(2);
        assertThat(d.get(0).isClutchVencido()).isTrue();
        assertThat(d.get(0).getClutchContra()).isEqualTo(3);
        assertThat(d.get(0).getTitulo()).isEqualTo("Clutch 1v3");
        assertThat(d.get(0).getPontuacao()).isGreaterThan(d.get(1).getPontuacao());
    }

    /**
     * O ponto todo do highlight: um round mediano não é destaque. Publicar
     * "1 kill" como melhor momento seria elogiar o que não houve.
     */
    @Test
    @DisplayName("partida sem nenhum round acima do piso devolve lista vazia")
    void semDestaqueDevolveVazio() {
        Round r1 = round(1, Team.TR);
        r1.addEvent(kill(eu, inimigo, 100));

        Round r2 = round(2, Team.TR);
        r2.addEvent(kill(eu, inimigo, 100));

        assertThat(calcular()).isEmpty();
    }

    @Test
    @DisplayName("publica no máximo três destaques, do melhor para o pior")
    void publicaNoMaximoTres() {
        for (int n = 1; n <= 5; n++) {
            Round r = round(n, Team.CT);
            // Quanto maior o round, mais kills — a ordem da saída tem de ser
            // a inversa da ordem de criação.
            for (int k = 0; k < n + 2; k++) {
                r.addEvent(kill(eu, inimigo, 100 + k * 100));
            }
        }

        List<RoundHighlightsDTO.Destaque> d = calcular();

        assertThat(d).hasSize(3);
        assertThat(d).extracting(RoundHighlightsDTO.Destaque::getRoundNumber)
                .containsExactly(5, 4, 3);
        assertThat(d.get(0).getPontuacao()).isGreaterThanOrEqualTo(d.get(1).getPontuacao());
    }

    @Test
    @DisplayName("kill em aliado não vira destaque")
    void fogoAmigoNaoConta() {
        Round r = round(1, Team.CT);
        for (int k = 0; k < 4; k++) {
            MatchEvent tk = kill(eu, aliado, 100 + k * 100);
            tk.setVictimSide(Team.CT);   // aliado: mesmo lado do ator
            r.addEvent(tk);
        }

        assertThat(calcular()).isEmpty();
    }

    @Test
    @DisplayName("o desarme entra na conta e aparece na frase")
    void desarmeEntra() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(eu, inimigo, 100));
        r.addEvent(kill(eu, inimigo, 200));
        r.addEvent(MatchEvent.builder()
                .eventType(EventType.BOMB_DEFUSED).actor(eu).tick(500)
                .actorSide(Team.CT).build());

        RoundHighlightsDTO.Destaque d = calcular().get(0);

        assertThat(d.isDesarmou()).isTrue();
        assertThat(d.getDescricao()).contains("desarmou a bomba");
        // 2 kills + abertura(0,6) + defuse(1,5) + round vencido(0,5) = 4,6
        assertThat(d.getPontuacao()).isEqualTo(4.6);
    }

    /**
     * Dano sem kill precisa aparecer: é o round em que a pessoa deixou três
     * inimigos com 10 de vida e morreu. Ele não some, mas também não vence de
     * um round com kills.
     */
    @Test
    @DisplayName("dano alto sem kill sustenta um destaque")
    void danoSemKillContaMasNaoDomina() {
        Round r = round(1, Team.TR);
        for (int i = 0; i < 6; i++) {
            r.addEvent(dano(90, 100 + i * 50));
        }

        RoundHighlightsDTO.Destaque d = calcular().get(0);

        assertThat(d.getKills()).isZero();
        assertThat(d.getDamage()).isEqualTo(540);
        assertThat(d.getDescricao()).contains("540 de dano");
    }

    @Test
    @DisplayName("round perdido continua sendo destaque e é dito na frase")
    void roundPerdidoAparece() {
        Round r = round(1, Team.TR);
        r.addEvent(kill(eu, inimigo, 100));
        r.addEvent(kill(eu, inimigo, 200));
        r.addEvent(kill(eu, inimigo, 300));

        RoundHighlightsDTO.Destaque d = calcular().get(0);

        assertThat(d.isVenceuRound()).isFalse();
        assertThat(d.getDescricao()).endsWith("Round perdido.");
    }

    @Test
    @DisplayName("a primeira kill do round conta como abertura")
    void aberturaContada() {
        Round r = round(1, Team.CT);
        r.addEvent(kill(eu, inimigo, 100));
        r.addEvent(kill(eu, inimigo, 200));

        RoundHighlightsDTO.Destaque d = calcular().get(0);

        assertThat(d.isAbertura()).isTrue();
        assertThat(d.getDescricao()).contains("abriu o round");
    }

    @Test
    @DisplayName("partida ou jogador inexistente devolve vazio")
    void inexistenteDevolveVazio() {
        matchRepository.save(partida);

        assertThat(service.calcular(999_999L, STEAM_ID)).isEmpty();
        assertThat(service.calcular(partida.getId(), "76561198999999999")).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════

    private List<RoundHighlightsDTO.Destaque> calcular() {
        matchRepository.save(partida);
        return new ArrayList<>(
                service.calcular(partida.getId(), STEAM_ID).orElseThrow().getDestaques());
    }

    private Round round(int numero, Team vencedor) {
        Round r = Round.builder()
                .roundNumber(numero).startTick(numero * 10_000)
                .endTick(numero * 10_000 + 3000).durationSeconds(46.0)
                .winnerSide(vencedor)
                .endReason(vencedor == Team.CT
                        ? RoundEndReason.CT_WIN_ELIMINATION
                        : RoundEndReason.TR_WIN_ELIMINATION)
                .ctScoreAfter(numero).trScoreAfter(0)
                .build();
        partida.addRound(r);
        return r;
    }

    /** Kill do meu lado (CT) sobre o TR. */
    private MatchEvent kill(Player ator, Player vitima, int tick) {
        return kill(ator, Team.CT, vitima, tick);
    }

    /**
     * Kill com o lado do autor explícito.
     *
     * <p>O lado não dá para inferir de quem é o ator: o aliado mata do lado
     * CT e o inimigo mata do lado TR, e é essa diferença que a contagem de
     * vivos do clutch lê.</p>
     */
    private MatchEvent kill(Player ator, Team ladoAtor, Player vitima, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.KILL).actor(ator).victim(vitima).tick(tick)
                .weapon("ak47").isHeadshot(false)
                .actorSide(ladoAtor)
                .victimSide(ladoAtor == Team.CT ? Team.TR : Team.CT)
                .build();
    }

    private MatchEvent dano(int valor, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.DAMAGE).actor(eu).victim(inimigo).tick(tick)
                .weapon("ak47").damageAmount(valor)
                .actorSide(Team.CT).victimSide(Team.TR).build();
    }
}
