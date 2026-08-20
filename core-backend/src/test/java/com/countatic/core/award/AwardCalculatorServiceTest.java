package com.countatic.core.award;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes da escolha de título.
 *
 * <p>Um limiar errado aqui não quebra nada: entrega o título errado, que
 * parece funcionamento normal. Por isso os testes atacam as bordas — logo
 * abaixo e logo acima de cada corte — e a regra de desempate.</p>
 */
class AwardCalculatorServiceTest {

    private final AwardCalculatorService service = new AwardCalculatorService();

    // ═══════════════════════════════════════════════════════════════
    //  Volume mínimo
    // ═══════════════════════════════════════════════════════════════

    /**
     * Duas kills, ambas na cabeça, dão 100% de headshot. Sem exigir volume,
     * esse jogador sairia como "Cirurgião" — e o título perderia o sentido.
     */
    @Test
    @DisplayName("100% de headshot com poucas kills não rende título épico")
    void amostraPequenaNaoRendeTitulo() {
        var ctx = contexto(2, 5, 0, 20, Map.of("headshotPercentage", 100.0));

        assertThat(service.calcular(ctx)).isEmpty();
    }

    @Test
    @DisplayName("headshot alto com volume rende Cirurgião")
    void cirurgiaoExigeVolume() {
        var ctx = contexto(12, 10, 0, 22, Map.of("headshotPercentage", 62.0));

        assertThat(service.calcular(ctx)).contains(MatchAwardType.CIRURGIAO);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Desempate por prioridade
    // ═══════════════════════════════════════════════════════════════

    /**
     * As condições se sobrepõem de propósito: quem faz 2.5 de K/D quase sempre
     * também tem ADR alto. O desempate por prioridade é o que garante que o
     * título mais específico ganhe, em vez de a ordem do código decidir.
     */
    @Test
    @DisplayName("quando várias regras passam, vence a de maior prioridade")
    void prioridadeDesempata() {
        Map<String, Double> m = new HashMap<>();
        m.put("headshotPercentage", 65.0);   // Cirurgião, prioridade 10
        m.put("kdRatio", 2.4);               // Imparável, prioridade 9
        m.put("adr", 120.0);                 // Máquina de Dano, prioridade 7

        assertThat(service.calcular(contexto(20, 8, 3, 22, m)))
                .contains(MatchAwardType.CIRURGIAO);
    }

    /**
     * O cômico tem prioridade baixa justamente para não roubar a vez de um
     * épico: ninguém deve ser chamado de "Lanterna do Time" numa partida em
     * que carregou.
     */
    @Test
    @DisplayName("um épico sempre vence um cômico simultâneo")
    void epicoVenceComico() {
        Map<String, Double> m = new HashMap<>();
        m.put("kdRatio", 2.5);
        m.put("totalTeamBlinds", 6.0);       // Lanterna do Time
        m.put("totalEnemyBlinds", 1.0);

        assertThat(service.calcular(contexto(20, 8, 0, 22, m)))
                .contains(MatchAwardType.IMPARAVEL);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Ausente x péssimo
    // ═══════════════════════════════════════════════════════════════

    /**
     * "Longe Demais" pune quem insiste em duelos longos e perde. Quem nunca
     * teve duelo longo não tem a métrica — e não pode ser punido por isso.
     */
    @Test
    @DisplayName("métrica ausente não conta como desempenho péssimo")
    void ausenteNaoEhPessimo() {
        // Sem longRangeWinRate no mapa: o jogador não teve duelos longos.
        var ctx = contexto(10, 12, 2, 22, Map.of("closeRangeWinRate", 50.0));

        assertThat(service.calcular(ctx)).isNotEqualTo(Optional.of(MatchAwardType.LONGE_DEMAIS));
    }

    @Test
    @DisplayName("perder quase todo duelo longo rende Longe Demais")
    void longeDemaisComMetricaPresente() {
        var ctx = contexto(8, 15, 1, 22, Map.of("longRangeWinRate", 15.0));

        assertThat(service.calcular(ctx)).contains(MatchAwardType.LONGE_DEMAIS);
    }

    /**
     * Aqui zero é o padrão CERTO, ao contrário do resto do sistema: as
     * condições são "pelo menos tanto", e não ter lançado flash é exatamente o
     * que impede o título de utilitária.
     */
    @Test
    @DisplayName("quem não usou utilitária não ganha Arquiteto")
    void arquitetoExigeUtilitaria() {
        var ctx = contexto(15, 12, 3, 22, Map.of("flashEfficiency", 100.0));

        assertThat(service.calcular(ctx)).isNotEqualTo(Optional.of(MatchAwardType.ARQUITETO));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Ausência de título
    // ═══════════════════════════════════════════════════════════════

    /**
     * Não ter título é resposta comum e correta. Se toda partida rendesse um,
     * receber deixaria de significar algo.
     */
    @Test
    @DisplayName("desempenho mediano não rende título nenhum")
    void medianoNaoRendeTitulo() {
        Map<String, Double> m = new HashMap<>();
        m.put("kdRatio", 1.05);
        m.put("adr", 78.0);
        m.put("headshotPercentage", 45.0);
        m.put("deathsPerRound", 0.75);
        // Jogou utilitária: não é Turista, só mediano.
        m.put("totalFlashesThrown", 4.0);
        m.put("totalSmokesThrown", 3.0);
        m.put("openingDuels", 3.0);

        assertThat(service.calcular(contexto(16, 15, 4, 22, m))).isEmpty();
    }

    @Test
    @DisplayName("mapa de métricas vazio não estoura")
    void semMetricasNaoEstoura() {
        assertThat(service.calcular(contexto(0, 0, 0, 0, Map.of()))).isEmpty();
        assertThat(service.calcular(
                new AwardCalculatorService.AwardContext(0, 0, 0, 0, null))).isEmpty();
    }

    /** Todo título precisa ser alcançável, senão é código morto com cara de feature. */
    @Test
    @DisplayName("Rei do Clutch sai com dois clutches vencidos")
    void reiDoClutch() {
        assertThat(service.calcular(contexto(14, 12, 2, 22, Map.of("clutchesWon", 2.0))))
                .contains(MatchAwardType.REI_DO_CLUTCH);
    }

    @Test
    @DisplayName("a prioridade dos cômicos é menor que a dos épicos")
    void comicosTemPrioridadeMenor() {
        int menorEpico = java.util.Arrays.stream(MatchAwardType.values())
                .filter(t -> t.getCategoria() == MatchAwardType.Categoria.EPICO)
                .mapToInt(MatchAwardType::getPrioridade).min().orElseThrow();

        int maiorComico = java.util.Arrays.stream(MatchAwardType.values())
                .filter(t -> t.getCategoria() == MatchAwardType.Categoria.COMICO)
                .mapToInt(MatchAwardType::getPrioridade).max().orElseThrow();

        assertThat(maiorComico).isLessThan(menorEpico);
    }

    /**
     * Regressão encontrada ao escrever estes testes: as regras de teto
     * ("no máximo tanto") disparavam para todo mundo, porque uma métrica
     * ausente valia zero. Quem não tinha mortes por round registradas parecia
     * não ter morrido nenhuma vez e ganhava "Muralha".
     */
    @Test
    @DisplayName("regra de teto não dispara com a métrica ausente")
    void tetoExigeMetricaMedida() {
        // Sem deathsPerRound e sem os contadores de utilitária no mapa.
        var ctx = contexto(12, 14, 2, 22, Map.of("headshotPercentage", 40.0));

        Optional<MatchAwardType> t = service.calcular(ctx);

        assertThat(t).isNotEqualTo(Optional.of(MatchAwardType.MURALHA));
        assertThat(t).isNotEqualTo(Optional.of(MatchAwardType.TURISTA));
        assertThat(t).isNotEqualTo(Optional.of(MatchAwardType.LONGE_DEMAIS));
    }

    /** Medido e zerado é diferente de não medido — aí o título vale. */
    @Test
    @DisplayName("quem realmente não usou nada ganha Turista")
    void turistaComMetricasMedidas() {
        Map<String, Double> m = new HashMap<>();
        m.put("totalFlashesThrown", 0.0);
        m.put("totalSmokesThrown", 0.0);
        m.put("openingDuels", 0.0);

        assertThat(service.calcular(contexto(8, 14, 1, 22, m)))
                .contains(MatchAwardType.TURISTA);
    }

    // ═══════════════════════════════════════════════════════════════

    private static AwardCalculatorService.AwardContext contexto(
            int kills, int deaths, int assists, int rounds, Map<String, Double> metricas) {
        return new AwardCalculatorService.AwardContext(kills, deaths, assists, rounds, metricas);
    }
}
