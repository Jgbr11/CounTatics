package com.countatic.core.strategy;

import com.countatic.core.dto.stats.Insight;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.impl.AimStatStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AimStatStrategyTest {

    private AimStatStrategy aimStatStrategy;
    private Player testPlayer;
    private Match testMatch;

    @BeforeEach
    void setUp() {
        aimStatStrategy = new AimStatStrategy();

        testPlayer = Player.builder()
                .id(1L)
                .steamId64("76561198012345678")
                .displayName("Fallen")
                .build();

        testMatch = Match.builder()
                .id(100L)
                .mapName("de_mirage")
                .totalRounds(10)
                .scoreCT(6)
                .scoreTR(4)
                .build();
    }

    @Test
    @DisplayName("Deve calcular 50% de Headshot quando metade das kills forem headshot")
    void shouldCalculateFiftyPercentHeadshotRate() {
        Round round1 = Round.builder().id(1L).roundNumber(1).build();

        // Kill 1: Headshot
        MatchEvent kill1 = MatchEvent.builder()
                .eventType(EventType.KILL)
                .actor(testPlayer)
                .isHeadshot(true)
                .build();

        // Kill 2: Não-Headshot
        MatchEvent kill2 = MatchEvent.builder()
                .eventType(EventType.KILL)
                .actor(testPlayer)
                .isHeadshot(false)
                .build();

        round1.addEvent(kill1);
        round1.addEvent(kill2);
        testMatch.addRound(round1);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result).isNotNull();
        assertThat(result.getCategory()).isEqualTo("Aim");
        assertThat(result.getSteamId64()).isEqualTo("76561198012345678");

        Double hsPct = result.getMetrics().get("headshotPercentage");
        assertThat(hsPct).isEqualTo(50.0);

        assertThat(result.getMetrics().get("totalKills")).isEqualTo(2.0);
        assertThat(result.getMetrics().get("totalHeadshotKills")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("killsPerRound")).isEqualTo(0.2); // 2 kills / 10 rounds
    }

    @Test
    @DisplayName("Deve calcular K/D Ratio corretamente")
    void shouldCalculateKdRatioCorrectly() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // 3 kills do jogador
        for (int i = 0; i < 3; i++) {
            round.addEvent(MatchEvent.builder()
                    .eventType(EventType.KILL)
                    .actor(testPlayer)
                    .isHeadshot(true)
                    .build());
        }

        // 1 morte do jogador
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.KILL)
                .victim(testPlayer)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("kdRatio")).isEqualTo(3.0);
        assertThat(result.getMetrics().get("totalKills")).isEqualTo(3.0);
        assertThat(result.getMetrics().get("totalDeaths")).isEqualTo(1.0);
        assertThat(result.getInsights().get("kdRatio").texto()).contains("K/D excelente");
        assertThat(result.getInsights().get("kdRatio").gravidade())
                .isEqualTo(Insight.Severidade.SUCESSO);
    }

    /**
     * Disparo com a mira exatamente sobre a cabeça do inimigo.
     *
     * <p>Atirador na origem olhando para +X (yaw 0, pitch 0); inimigo 500
     * unidades à frente, na mesma altura. Erro angular esperado: 0°.</p>
     */
    @Test
    @DisplayName("mira exatamente na cabeça do inimigo dá erro angular zero")
    void miraPerfeitaDaErroZero() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        round.addEvent(disparo(0.0, 0.0, 0, 0, 0, 500, 0, 0));
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("crosshairPlacementScore")).isEqualTo(100.0);
        assertThat(result.getMetrics().get("medianCrosshairErrorDegrees")).isEqualTo(0.0);
    }

    /**
     * REGRESSÃO — o defeito central da fórmula anterior.
     *
     * <p>Ela media apenas o valor absoluto do pitch: um disparo com pitch 0°
     * contava como mira perfeita mesmo com o inimigo em outra direção. Aqui o
     * atirador olha para +X (pitch 0, que a fórmula antiga premiava) enquanto o
     * inimigo está a 90° no eixo Y. Se voltar a pontuar bem, a regressão voltou.</p>
     */
    @Test
    @DisplayName("pitch zero com inimigo a 90° NÃO é boa mira")
    void pitchZeroComInimigoDeLadoNaoEhBoaMira() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        // Olhando para +X; inimigo em +Y (90° de erro).
        round.addEvent(disparo(0.0, 0.0, 0, 0, 0, 0, 500, 0));
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("crosshairPlacementScore")).isEqualTo(0.0);
        assertThat(result.getMetrics().get("medianCrosshairErrorDegrees")).isEqualTo(90.0);
    }

    @Test
    @DisplayName("pitch positivo aponta para BAIXO (convenção da Source)")
    void pitchPositivoApontaParaBaixo() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        // Pitch +45° = olhando para baixo. Inimigo abaixo e à frente, a 45°.
        round.addEvent(disparo(0.0, 45.0, 0, 0, 0, 500, 0, -500));
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        // Se o sinal do pitch estivesse invertido, o erro seria 90°, não 0°.
        assertThat(result.getMetrics().get("medianCrosshairErrorDegrees")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score é a proporção de disparos dentro de 5° da cabeça")
    void scoreEhProporcaoDentroDoLimiar() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        round.addEvent(disparo(0.0, 0.0, 0, 0, 0, 500, 0, 0));    // 0°  → bom
        round.addEvent(disparo(0.0, 0.0, 0, 0, 0, 0, 500, 0));    // 90° → ruim
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("crosshairPlacementScore")).isEqualTo(50.0);
        assertThat(result.getMetrics().get("evaluatedShots")).isEqualTo(2.0);
    }

    /**
     * Sem inimigo anexado, o disparo não é avaliável — spray em parede e tiro
     * às cegas não dizem nada sobre posicionamento de mira.
     */
    @Test
    @DisplayName("disparo sem inimigo no cone não entra na métrica")
    void disparoSemInimigoNaoConta() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .viewAngleX(0.0).viewAngleY(0.0)
                .actorPositionX(0.0).actorPositionY(0.0).actorPositionZ(0.0)
                // sem victimPosition: o parser não achou inimigo no cone frontal
                .build());
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        // Sem disparo avaliável, as métricas de mira nem são publicadas —
        // devolver 0.0 seria indistinguível de "mira péssima".
        assertThat(result.getMetrics().get("medianCrosshairErrorDegrees")).isNull();
        assertThat(result.getMetrics().get("evaluatedShots")).isNull();
    }

    @Test
    @DisplayName("Não publica crosshairPlacementScore quando nenhum disparo tem alvo — "
            + "0.0 entraria na base de comparação como se fosse desempenho real")
    void naoPublicaCrosshairSemDisparoAvaliavel() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // Disparo com ângulo de visão, mas sem a posição do inimigo: é
        // exatamente o formato que o parser Go produzia antes do commit 473f198,
        // e é o que está gravado nas partidas já analisadas.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .tick(100)
                .actorPositionX(0.0).actorPositionY(0.0).actorPositionZ(64.0)
                .viewAngleX(0.0).viewAngleY(0.0)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics())
                .doesNotContainKey("crosshairPlacementScore")
                .doesNotContainKey("medianCrosshairErrorDegrees")
                .doesNotContainKey("evaluatedShots");
    }

    @Test
    @DisplayName("Publica crosshairPlacementScore quando o disparo tem a cabeça do inimigo anexada")
    void publicaCrosshairComDisparoAvaliavel() {
        Round round = Round.builder().id(1L).roundNumber(1).build();

        // Mira exatamente sobre a cabeça: yaw 0 aponta para +X na convenção
        // Source, e o alvo está 100 unidades à frente, na mesma altura dos olhos.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .tick(100)
                .actorPositionX(0.0).actorPositionY(0.0).actorPositionZ(64.0)
                .victimPositionX(100.0).victimPositionY(0.0).victimPositionZ(64.0)
                .viewAngleX(0.0).viewAngleY(0.0)
                .build());

        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics().get("crosshairPlacementScore")).isEqualTo(100.0);
        assertThat(result.getMetrics().get("evaluatedShots")).isEqualTo(1.0);
        assertThat(result.getMetrics().get("medianCrosshairErrorDegrees")).isEqualTo(0.0);
    }

    // ═══════════════════════════════════════════════════════════════
    //  AUSÊNCIA ≠ ZERO
    //
    //  Cada métrica tem dois testes: denominador zero → chave ausente,
    //  e denominador > 0 com numerador 0 → chave presente valendo 0.0.
    //  O segundo é o que impede a correção de virar um bug pior, apagando
    //  desempenho real de quem jogou e foi mal.
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sem kills não publica headshotPercentage — não existe proporção de 0 kills")
    void semKillsNaoPublicaHeadshotPercentage() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        // Só uma morte do jogador: ele participou da partida, mas não matou ninguém.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.KILL)
                .victim(testPlayer)
                .build());
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics()).doesNotContainKey("headshotPercentage");
        assertThat(result.getInsights()).doesNotContainKey("headshotPercentage");
    }

    @Test
    @DisplayName("com kills e nenhum headshot publica headshotPercentage = 0.0 — é desempenho medido")
    void killsSemHeadshotPublicamZeroReal() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        for (int i = 0; i < 3; i++) {
            round.addEvent(MatchEvent.builder()
                    .eventType(EventType.KILL)
                    .actor(testPlayer)
                    .isHeadshot(false)
                    .build());
        }
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics()).containsEntry("headshotPercentage", 0.0);
    }

    @Test
    @DisplayName("sem rounds não publica killsPerRound nem deathsPerRound")
    void semRoundsNaoPublicaMediasPorRound() {
        Match semRounds = Match.builder()
                .id(101L)
                .mapName("de_mirage")
                .totalRounds(0)
                .build();

        PlayerStatResult result = aimStatStrategy.calculate(semRounds, testPlayer);

        assertThat(result.getMetrics())
                .doesNotContainKey("killsPerRound")
                .doesNotContainKey("deathsPerRound");
    }

    /**
     * {@code deathsPerRound} é a única métrica com {@code maiorEhMelhor = false}
     * no BaselineService: um 0.0 fabricado aqui não afundaria a distribuição —
     * daria percentil 100 a quem não jogou. Mas 0 mortes em 10 rounds é um round
     * de sobrevivência perfeita, e apagar isso seria pior que o bug original.
     */
    @Test
    @DisplayName("com rounds e sem mortes publica deathsPerRound = 0.0 — sobreviver é desempenho")
    void roundsSemMortesPublicamZeroReal() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.KILL)
                .actor(testPlayer)
                .isHeadshot(true)
                .build());
        testMatch.addRound(round); // totalRounds = 10

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics()).containsEntry("deathsPerRound", 0.0);
        assertThat(result.getMetrics()).containsEntry("killsPerRound", 0.1);
    }

    @Test
    @DisplayName("sem kills e sem mortes não publica kdRatio — não houve duelo nenhum")
    void semDuelosNaoPublicaKdRatio() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        // Um evento qualquer que não seja duelo, só para a partida não ficar vazia.
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.SMOKE_THROWN)
                .actor(testPlayer)
                .build());
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics()).doesNotContainKey("kdRatio");
        assertThat(result.getInsights()).doesNotContainKey("kdRatio");
    }

    /**
     * 0 mortes COM kills não é ausência de denominador: é a convenção do cenário
     * competitivo (K/D = número de kills) e precisa continuar sendo publicada.
     */
    @Test
    @DisplayName("kills sem nenhuma morte publica kdRatio igual ao número de kills")
    void killsSemMortesMantemConvencao() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        for (int i = 0; i < 2; i++) {
            round.addEvent(MatchEvent.builder()
                    .eventType(EventType.KILL)
                    .actor(testPlayer)
                    .isHeadshot(false)
                    .build());
        }
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics()).containsEntry("kdRatio", 2.0);
    }

    /**
     * 0 kills COM mortes tem denominador: o jogador entrou em duelos e perdeu.
     * K/D 0.0 aqui é desempenho medido, não ausência.
     */
    @Test
    @DisplayName("mortes sem nenhuma kill publica kdRatio = 0.0 — perder duelos é desempenho")
    void mortesSemKillsPublicamZeroReal() {
        Round round = Round.builder().id(1L).roundNumber(1).build();
        round.addEvent(MatchEvent.builder()
                .eventType(EventType.KILL)
                .victim(testPlayer)
                .build());
        testMatch.addRound(round);

        PlayerStatResult result = aimStatStrategy.calculate(testMatch, testPlayer);

        assertThat(result.getMetrics()).containsEntry("kdRatio", 0.0);
    }

    /**
     * {@code getTotalRounds()} é {@code Integer}: desempacotá-lo direto num
     * {@code int} explodia em NPE numa partida ainda não consolidada.
     */
    @Test
    @DisplayName("totalRounds nulo não lança NPE")
    void totalRoundsNuloNaoQuebra() {
        Match semTotal = Match.builder()
                .id(102L)
                .mapName("de_mirage")
                .build();

        PlayerStatResult result = aimStatStrategy.calculate(semTotal, testPlayer);

        assertThat(result).isNotNull();
        assertThat(result.getMetrics())
                .doesNotContainKey("killsPerRound")
                .doesNotContainKey("deathsPerRound");
    }

    /** Monta um WEAPON_FIRE com posição dos olhos, mira e cabeça do inimigo. */
    private MatchEvent disparo(double yaw, double pitch,
                               double olhoX, double olhoY, double olhoZ,
                               double alvoX, double alvoY, double alvoZ) {
        return MatchEvent.builder()
                .eventType(EventType.WEAPON_FIRE)
                .actor(testPlayer)
                .viewAngleX(yaw).viewAngleY(pitch)
                .actorPositionX(olhoX).actorPositionY(olhoY).actorPositionZ(olhoZ)
                .victimPositionX(alvoX).victimPositionY(alvoY).victimPositionZ(alvoZ)
                .build();
    }
}
