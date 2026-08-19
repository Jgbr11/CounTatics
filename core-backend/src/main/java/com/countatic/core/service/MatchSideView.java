package com.countatic.core.service;

import com.countatic.core.entity.Match;
import com.countatic.core.entity.MatchEvent;
import com.countatic.core.entity.Player;
import com.countatic.core.entity.Round;
import com.countatic.core.entity.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * Recorta uma partida para os rounds em que um jogador atuou de um lado só.
 *
 * <p><b>Por que existe.</b> No CS a postura muda por completo entre CT e TR, e
 * uma eficiência de flash média esconde que ela despenca de um dos lados. A
 * forma óbvia de responder isso seria acrescentar um parâmetro de lado ao
 * {@code calculate} das quatro Strategies — o que espalharia a mesma
 * ramificação por todas elas e por todo cálculo futuro.</p>
 *
 * <p>Em vez disso, <b>filtra-se a entrada e não o algoritmo</b>: monta-se uma
 * visão da partida contendo apenas os rounds daquele lado, e as Strategies
 * rodam sobre ela sem saber que existe recorte. Nenhuma linha delas mudou.</p>
 *
 * <p><b>O detalhe que faz a conta fechar</b> é o {@code totalRounds} da visão
 * ser a quantidade de rounds <i>daquele lado</i>. Sem isso, toda métrica por
 * round sairia dividida pelo total da partida e o valor de cada lado ficaria
 * pela metade.</p>
 *
 * <p><b>Os eventos não são filtrados, só os rounds.</b> Um round pertence
 * inteiro a um lado — o time só troca no intervalo. E remover os eventos dos
 * outros jogadores quebraria a detecção de trade, que precisa saber quem do
 * time morreu antes.</p>
 *
 * <p>As instâncias criadas aqui são <b>destacadas</b>: usam o builder, que
 * atribui as listas diretamente, em vez de {@code addRound}/{@code addEvent},
 * que reapontariam a referência inversa das entidades reais e corromperiam o
 * contexto de persistência.</p>
 */
final class MatchSideView {

    private MatchSideView() {
    }

    /**
     * Visão da partida com os rounds em que o jogador atuou pelo lado dado.
     *
     * @return visão possivelmente vazia — jogador que entrou no segundo tempo
     *         não tem rounds do primeiro lado, e isso é resposta legítima
     */
    static Match recortar(Match original, Player jogador, Team lado) {
        List<Round> doLado = new ArrayList<>();

        for (Round round : original.getRounds()) {
            if (ladoDoJogadorNoRound(round, jogador) != lado) continue;

            doLado.add(Round.builder()
                    .id(round.getId())
                    .roundNumber(round.getRoundNumber())
                    .startTick(round.getStartTick())
                    .endTick(round.getEndTick())
                    .durationSeconds(round.getDurationSeconds())
                    .winnerSide(round.getWinnerSide())
                    .endReason(round.getEndReason())
                    .bombPlanted(round.getBombPlanted())
                    .bombDefused(round.getBombDefused())
                    // Os eventos são reaproveitados como estão: nada aqui os
                    // altera, e copiá-los custaria memória sem ganho.
                    .events(round.getEvents())
                    .build());
        }

        return Match.builder()
                .id(original.getId())
                .mapName(original.getMapName())
                .tickRate(original.getTickRate())
                .scoreCT(original.getScoreCT())
                .scoreTR(original.getScoreTR())
                .rankTier(original.getRankTier())
                .csRating(original.getCsRating())
                .playedAt(original.getPlayedAt())
                // A chave de tudo: por round significa por round DESTE lado.
                .totalRounds(doLado.size())
                .rounds(doLado)
                .build();
    }

    /**
     * De que lado o jogador estava neste round.
     *
     * <p>Sai dos próprios eventos — nem {@code Round} nem {@code Player}
     * guardam o time. Dentro de um round o lado não muda, então o primeiro
     * evento que identifica o jogador já responde.</p>
     *
     * @return {@code null} quando nenhum evento o identifica: alguém que
     *         passou o round sem disparar, matar ou morrer não pode ser
     *         atribuído a lado nenhum
     */
    private static Team ladoDoJogadorNoRound(Round round, Player jogador) {
        Long id = jogador.getId();

        for (MatchEvent e : round.getEvents()) {
            if (e.getActor() != null && id.equals(e.getActor().getId())
                    && e.getActorSide() != null) {
                return e.getActorSide();
            }
            if (e.getVictim() != null && id.equals(e.getVictim().getId())
                    && e.getVictimSide() != null) {
                return e.getVictimSide();
            }
        }
        return null;
    }
}
