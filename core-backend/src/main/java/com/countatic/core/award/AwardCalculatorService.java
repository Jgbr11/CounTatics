package com.countatic.core.award;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Escolhe o título de um jogador na partida.
 *
 * <p>Cada título é uma <b>regra independente</b>: um predicado sobre o
 * desempenho. Todas são avaliadas, e vence a de maior prioridade entre as que
 * passaram. A alternativa — uma cadeia de {@code if/else} — faria a ordem do
 * código determinar o resultado, e acrescentar um título no meio mudaria
 * silenciosamente quem ganha os outros.</p>
 *
 * <p><b>Toda regra exige volume mínimo.</b> Sem isso, quem deu duas kills na
 * cabeça em duas kills totais sairia como "Cirurgião" com 100% de headshot. Um
 * título vindo de amostra minúscula desmoraliza os outros.</p>
 */
@Slf4j
@Service
public class AwardCalculatorService {

    /** Um título e a condição que o concede. */
    private record Regra(MatchAwardType tipo, Predicate<AwardContext> condicao) {
    }

    private final List<Regra> regras = new ArrayList<>();

    public AwardCalculatorService() {
        // ─── Épicos ───────────────────────────────────────────────────
        regra(MatchAwardType.CIRURGIAO, c ->
                c.kills() >= 10 && c.metrica("headshotPercentage") >= 60);

        regra(MatchAwardType.IMPARAVEL, c ->
                c.kills() >= 15 && c.metrica("kdRatio") >= 2.0);

        regra(MatchAwardType.REI_DO_CLUTCH, c ->
                c.metrica("clutchesWon") >= 2);

        regra(MatchAwardType.SNIPER, c ->
                c.temMetrica("longRangeWinRate") && c.metrica("longRangeWinRate") >= 70);

        regra(MatchAwardType.ABRE_ALAS, c ->
                c.metrica("openingDuels") >= 5 && c.metrica("openingDuelWinRate") >= 70);

        regra(MatchAwardType.MAQUINA_DE_DANO, c ->
                c.rounds() >= 10 && c.metrica("adr") >= 100);

        // ─── Neutros ──────────────────────────────────────────────────
        regra(MatchAwardType.ARQUITETO, c ->
                c.metrica("flashEfficiency") >= 55
                        && c.metrica("totalFlashesThrown") >= 4
                        && c.metrica("utilityDamagePerRound") >= 8);

        regra(MatchAwardType.SOMBRA, c ->
                c.metrica("tradeKills") >= 4
                        && c.metrica("tradeKills") >= c.metrica("tradedDeaths"));

        regra(MatchAwardType.MURALHA, c ->
                c.rounds() >= 10 && c.noMaximo("deathsPerRound", 0.55));

        regra(MatchAwardType.DUELISTA_DE_PERTO, c ->
                c.temMetrica("closeRangeWinRate") && c.metrica("closeRangeWinRate") >= 65);

        // ─── Cômicos ──────────────────────────────────────────────────
        regra(MatchAwardType.LANTERNA_DO_TIME, c ->
                c.metrica("totalTeamBlinds") >= 3
                        && c.metrica("totalTeamBlinds") > c.metrica("totalEnemyBlinds"));

        regra(MatchAwardType.PRIMEIRO_A_CAIR, c ->
                c.deaths() >= 8 && c.metrica("earlyDeathRate") >= 50);

        regra(MatchAwardType.TURISTA, c ->
                c.rounds() >= 10
                        && c.zerado("totalFlashesThrown")
                        && c.zerado("totalSmokesThrown")
                        && c.zerado("openingDuels"));

        regra(MatchAwardType.LONGE_DEMAIS, c ->
                c.noMaximo("longRangeWinRate", 20));
    }

    private void regra(MatchAwardType tipo, Predicate<AwardContext> condicao) {
        regras.add(new Regra(tipo, condicao));
    }

    /**
     * Título do jogador, se ele merecer algum.
     *
     * <p>{@code Optional.empty()} é resposta comum e correta: a maioria das
     * partidas de um jogador mediano não rende título nenhum, e inventar um
     * para todo mundo tiraria o valor de recebê-lo.</p>
     */
    public Optional<MatchAwardType> calcular(AwardContext contexto) {
        return regras.stream()
                .filter(r -> testar(r, contexto))
                .map(Regra::tipo)
                .max(Comparator.comparingInt(MatchAwardType::getPrioridade));
    }

    /**
     * Uma regra quebrada não pode impedir as outras de serem avaliadas.
     *
     * <p>As condições leem métricas que podem não existir; o contexto já
     * devolve zero para ausentes, mas um erro de programação numa regra nova
     * derrubaria o cálculo de todos os títulos da partida.</p>
     */
    private boolean testar(Regra regra, AwardContext contexto) {
        try {
            return regra.condicao().test(contexto);
        } catch (RuntimeException e) {
            log.warn("Regra do título {} falhou: {}", regra.tipo(), e.getMessage());
            return false;
        }
    }

    /**
     * Desempenho de um jogador numa partida, achatado para as regras.
     *
     * @param metricas todas as categorias reunidas num mapa só — uma regra
     *                 pode cruzar mira com utilitária, e exigir que ela saiba
     *                 de que categoria veio cada número seria ruído
     */
    public record AwardContext(int kills, int deaths, int assists, int rounds,
                               Map<String, Double> metricas) {

        /**
         * Valor da métrica, ou <b>zero</b> se ela não foi medida.
         *
         * <p>Aqui zero é o padrão certo, ao contrário do resto do sistema: as
         * condições são todas do tipo "pelo menos tanto", e uma métrica ausente
         * significa exatamente que o jogador não fez aquilo. Quem não lançou
         * flash não pode ganhar "Arquiteto".</p>
         *
         * <p>As regras cujo sentido depende de a métrica <i>existir</i> — as de
         * taxa por distância, que exigem duelos suficientes — usam
         * {@link #temMetrica} antes, para não confundir "não medido" com
         * "péssimo".</p>
         */
        public double metrica(String chave) {
            Double v = metricas == null ? null : metricas.get(chave);
            return v == null ? 0.0 : v;
        }

        public boolean temMetrica(String chave) {
            return metricas != null && metricas.get(chave) != null;
        }

        /**
         * "No máximo tanto" — e a métrica precisa ter sido medida.
         *
         * <p>Existe porque {@link #metrica} devolve zero para ausente, o que é
         * certo para condições de piso e <b>desastroso</b> para as de teto:
         * quem não teve mortes por round registradas pareceria não ter morrido
         * nenhuma vez, e ganharia o título de quem menos morre. É a mesma
         * confusão entre <i>ausente</i> e <i>zero</i> que as Strategies já
         * tinham resolvido do outro lado.</p>
         */
        public boolean noMaximo(String chave, double limite) {
            return temMetrica(chave) && metrica(chave) <= limite;
        }

        /** Mediu e deu zero — diferente de não ter medido. */
        public boolean zerado(String chave) {
            return temMetrica(chave) && metrica(chave) == 0.0;
        }
    }
}
