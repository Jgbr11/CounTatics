package com.countatic.core.service;

import com.countatic.core.dto.stats.WeaponStatsDTO;
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
 * Testes do desempenho por arma.
 *
 * <p>O ponto delicado é a <b>taxa de acerto</b>. Contar eventos de dano
 * direto daria mais de 100% em shotgun, porque um tiro gera um evento por
 * pellet. O cálculo volta ao tick do disparo justamente para colapsar isso, e
 * é esse comportamento que os testes cercam.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class WeaponStatsServiceTest {

    private static final String STEAM_ID = "76561199110265389";

    @Autowired
    private WeaponStatsService service;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRepository playerRepository;

    private Player eu;
    private Player inimigo;
    private Match partida;
    private Round round;

    @Autowired
    private com.countatic.core.repository.PlayerMatchStatsRepository statsRepository;

    @BeforeEach
    void preparar() {
        // Os desempenhos saem primeiro: eles têm chave estrangeira para as
        // partidas, e outras classes de teste deixam linhas para trás. Sem
        // isto, o delete das partidas viola a FK e a falha aparece aqui, num
        // teste que não tem nada a ver.
        statsRepository.deleteAll();
        matchRepository.deleteAll();
        playerRepository.deleteAll();

        eu = playerRepository.save(Player.builder()
                .steamId64(STEAM_ID).displayName("JGBR11").build());
        inimigo = playerRepository.save(Player.builder()
                .steamId64("76561198000000002").displayName("INIMIGO").build());

        partida = Match.builder()
                .demoFileHash("hash-armas").demoFileName("t.dem").mapName("de_mirage")
                .durationSeconds(1800).scoreCT(13).scoreTR(8)
                .totalRounds(21).tickRate(64)
                .status(MatchStatus.COMPLETED).playedAt(Instant.now())
                .build();

        round = Round.builder()
                .roundNumber(1).startTick(0).endTick(3000).durationSeconds(46.0)
                .winnerSide(Team.CT).endReason(RoundEndReason.CT_WIN_ELIMINATION)
                .ctScoreAfter(1).trScoreAfter(0)
                .build();
        partida.addRound(round);
    }

    private WeaponStatsDTO calcular() {
        matchRepository.save(partida);
        return service.calcular(partida.getId(), STEAM_ID).orElseThrow();
    }

    private WeaponStatsDTO.Arma arma(WeaponStatsDTO d, String id) {
        return d.getArmas().stream().filter(a -> a.getId().equals(id)).findFirst().orElseThrow();
    }

    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("kills e headshots são contados por arma")
    void contaPorArma() {
        round.addEvent(kill("ak47", true, 100));
        round.addEvent(kill("ak47", false, 200));
        round.addEvent(kill("awp", false, 300));

        WeaponStatsDTO d = calcular();

        assertThat(arma(d, "ak47").getKills()).isEqualTo(2);
        assertThat(arma(d, "ak47").getHeadshotPercentage()).isEqualTo(50.0);
        assertThat(arma(d, "awp").getKills()).isEqualTo(1);
    }

    @Test
    @DisplayName("a arma que mais matou vem primeiro")
    void ordenaPorKills() {
        round.addEvent(kill("awp", false, 100));
        round.addEvent(kill("ak47", false, 200));
        round.addEvent(kill("ak47", false, 300));

        assertThat(calcular().getArmas().get(0).getId()).isEqualTo("ak47");
    }

    /**
     * Um tiro de shotgun gera um evento de dano por pellet. Contando eventos,
     * cinco pellets num tiro dariam 500% de acerto.
     */
    @Test
    @DisplayName("vários pellets no mesmo tiro contam como um acerto só")
    void pelletsNaoInflamAPrecisao() {
        // 20 tiros; o primeiro acerta com 5 pellets, os demais erram.
        for (int i = 0; i < 20; i++) {
            round.addEvent(tiro("nova", 1000 + i * 100));
        }
        for (int p = 0; p < 5; p++) {
            round.addEvent(dano("nova", 12, 1000));
        }

        WeaponStatsDTO.Arma nova = arma(calcular(), "nova");

        assertThat(nova.getTiros()).isEqualTo(20);
        assertThat(nova.getAcertos()).isEqualTo(1);
        assertThat(nova.getAccuracy()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("dano um tick depois do disparo ainda conta como acerto")
    void janelaDeUmTick() {
        for (int i = 0; i < 15; i++) {
            round.addEvent(tiro("ak47", 1000 + i * 50));
        }
        // Dano registrado um tick após o disparo.
        round.addEvent(dano("ak47", 27, 1001));

        assertThat(arma(calcular(), "ak47").getAcertos()).isEqualTo(1);
    }

    /**
     * Três tiros com um acerto não são "33% de precisão" — são três tiros.
     */
    @Test
    @DisplayName("a precisão exige tiros suficientes")
    void precisaoExigeAmostra() {
        round.addEvent(tiro("awp", 100));
        round.addEvent(tiro("awp", 200));
        round.addEvent(dano("awp", 100, 100));

        WeaponStatsDTO.Arma awp = arma(calcular(), "awp");

        assertThat(awp.getTiros()).isEqualTo(2);
        assertThat(awp.getAccuracy()).isNull();
    }

    @Test
    @DisplayName("kill sem nenhuma kill não publica headshot")
    void semKillsNaoPublicaHeadshot() {
        for (int i = 0; i < 15; i++) {
            round.addEvent(tiro("ak47", 1000 + i * 50));
        }

        assertThat(arma(calcular(), "ak47").getHeadshotPercentage()).isNull();
    }

    /** Fogo amigo não descreve desempenho com a arma. */
    @Test
    @DisplayName("kill em aliado não entra")
    void fogoAmigoNaoEntra() {
        MatchEvent tk = kill("ak47", false, 100);
        tk.setVictimSide(Team.CT);
        round.addEvent(tk);

        assertThat(calcular().getArmas()).isEmpty();
    }

    @Test
    @DisplayName("partida ou jogador inexistente devolve vazio")
    void inexistenteDevolveVazio() {
        matchRepository.save(partida);

        assertThat(service.calcular(999_999L, STEAM_ID)).isEmpty();
        assertThat(service.calcular(partida.getId(), "76561198999999999")).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════

    private MatchEvent kill(String arma, boolean headshot, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.KILL).actor(eu).victim(inimigo).tick(tick)
                .weapon(arma).isHeadshot(headshot)
                .actorSide(Team.CT).victimSide(Team.TR).build();
    }

    private MatchEvent dano(String arma, int valor, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.DAMAGE).actor(eu).victim(inimigo).tick(tick)
                .weapon(arma).damageAmount(valor)
                .actorSide(Team.CT).victimSide(Team.TR).build();
    }

    private MatchEvent tiro(String arma, int tick) {
        return MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE).actor(eu).tick(tick)
                .weapon(arma).actorSide(Team.CT).build();
    }
}
