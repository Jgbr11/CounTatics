package com.countatic.core.service;

import com.countatic.core.dto.stats.WeaponStatsDTO;
import com.countatic.core.entity.EventType;
import com.countatic.core.entity.Match;
import com.countatic.core.entity.MatchEvent;
import com.countatic.core.entity.Player;
import com.countatic.core.entity.Round;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.repository.RoundRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Desempenho por arma numa partida.
 *
 * <p>O campo {@code weapon} está em todo kill, dano e disparo desde sempre e
 * nunca tinha sido lido. É o dado que responde "com o que eu jogo bem" — 50%
 * de headshot no agregado pode ser 70% de AK e 20% de AWP, e o treino de cada
 * caso é outro.</p>
 *
 * <p>Não é uma Strategy: as Strategies produzem um mapa plano de métricas por
 * categoria, e aqui o eixo é a <b>arma</b>, com uma linha por item. Forçar
 * esse formato no outro daria chaves como {@code "killsComAk47"} e a interface
 * teria de desmontá-las por prefixo.</p>
 */
@Slf4j
@Service
public class WeaponStatsService {

    /**
     * Disparos mínimos para publicar a taxa de acerto.
     *
     * <p>Três tiros de AWP com um acerto não são "33% de precisão"; são três
     * tiros. Mesma regra das demais taxas do sistema.</p>
     */
    private static final int TIROS_MINIMOS = 15;

    /**
     * Folga em ticks entre o disparo e o dano que ele causou.
     *
     * <p>Bala em CS é hitscan: o dano cai no mesmo tick do tiro. A folga de 1
     * cobre diferença de ordenação de eventos sem alcançar o disparo seguinte,
     * que em qualquer arma leva mais que dois ticks.</p>
     */
    private static final int JANELA_TICKS = 1;

    private final MatchRepository matchRepository;
    private final RoundRepository roundRepository;
    private final PlayerRepository playerRepository;

    public WeaponStatsService(MatchRepository matchRepository,
                              RoundRepository roundRepository,
                              PlayerRepository playerRepository) {
        this.matchRepository = matchRepository;
        this.roundRepository = roundRepository;
        this.playerRepository = playerRepository;
    }

    @Transactional(readOnly = true)
    public Optional<WeaponStatsDTO> calcular(Long matchId, String steamId64) {
        Optional<Match> partida = matchRepository.findByIdWithRounds(matchId);
        Optional<Player> jogador = playerRepository.findBySteamId64(steamId64);

        if (partida.isEmpty() || jogador.isEmpty()) {
            return Optional.empty();
        }

        roundRepository.findWithEventsByMatchId(matchId);

        Long playerId = jogador.get().getId();
        Map<String, Acumulador> porArma = new HashMap<>();

        for (Round round : partida.get().getRounds()) {
            for (MatchEvent e : round.getEvents()) {
                if (e.getActor() == null || !playerId.equals(e.getActor().getId())) continue;

                String arma = e.getWeapon();
                if (arma == null || arma.isBlank()) continue;

                Acumulador a = porArma.computeIfAbsent(arma, k -> new Acumulador());

                switch (e.getEventType()) {
                    case KILL -> {
                        // Fogo amigo não conta como desempenho com a arma.
                        if (mesmoLado(e)) break;
                        a.kills++;
                        if (Boolean.TRUE.equals(e.getIsHeadshot())) a.headshots++;
                    }
                    case DAMAGE -> {
                        if (mesmoLado(e)) break;
                        if (e.getDamageAmount() != null) a.dano += e.getDamageAmount();
                        if (e.getTick() != null) a.ticksComDano.add(e.getTick());
                    }
                    case WEAPON_FIRE -> {
                        if (e.getTick() != null) a.ticksDeTiro.add(e.getTick());
                    }
                    default -> { /* granadas e bomba não descrevem uso de arma */ }
                }
            }
        }

        List<WeaponStatsDTO.Arma> armas = new ArrayList<>(porArma.size());
        porArma.forEach((id, a) -> {
            // O acumulador nasce ao ver a arma no evento, antes de saber se
            // aquele evento conta. Uma kill em aliado, por exemplo, cria a
            // entrada e não soma nada — e a arma apareceria zerada na tabela,
            // afirmando um uso que não houve.
            if (a.vazio()) return;
            armas.add(a.montar(id));
        });

        // A arma que mais matou primeiro: é a que descreve como o jogador jogou.
        armas.sort(Comparator.comparingInt(WeaponStatsDTO.Arma::getKills).reversed()
                .thenComparing(WeaponStatsDTO.Arma::getId));

        return Optional.of(WeaponStatsDTO.builder()
                .matchId(matchId)
                .steamId64(steamId64)
                .armas(armas)
                .build());
    }

    private boolean mesmoLado(MatchEvent e) {
        return e.getActorSide() != null && e.getVictimSide() != null
                && e.getActorSide() == e.getVictimSide();
    }

    /** Contagens brutas de uma arma, antes de virarem taxas. */
    private static final class Acumulador {
        int kills;
        int headshots;
        int dano;

        /** Ticks em que houve disparo. */
        final Set<Integer> ticksDeTiro = new HashSet<>();

        /** Ticks em que houve dano. */
        final Set<Integer> ticksComDano = new HashSet<>();

        /** Nada aproveitável foi registrado para esta arma. */
        boolean vazio() {
            return kills == 0 && dano == 0 && ticksDeTiro.isEmpty();
        }

        WeaponStatsDTO.Arma montar(String id) {
            int tiros = ticksDeTiro.size();
            int acertos = contarAcertos();

            return WeaponStatsDTO.Arma.builder()
                    .id(id)
                    .kills(kills)
                    .headshots(headshots)
                    .headshotPercentage(kills > 0 ? round2((headshots * 100.0) / kills) : null)
                    .damage(dano)
                    .tiros(tiros)
                    .acertos(acertos)
                    .accuracy(tiros >= TIROS_MINIMOS ? round2((acertos * 100.0) / tiros) : null)
                    .build();
        }

        /**
         * Disparos que causaram dano, contados por <b>tick de disparo</b>.
         *
         * <p>Contar eventos de dano diretamente daria mais de 100% de precisão
         * em shotgun: um tiro espalha vários pellets e gera um evento de dano
         * por pellet. Voltando ao tick do disparo, os pellets do mesmo tiro
         * colapsam num acerto só, que é o que "acertei o tiro" significa.</p>
         */
        private int contarAcertos() {
            int acertos = 0;
            for (Integer tiro : ticksDeTiro) {
                for (int d = -JANELA_TICKS; d <= JANELA_TICKS; d++) {
                    if (ticksComDano.contains(tiro + d)) {
                        acertos++;
                        break;
                    }
                }
            }
            return acertos;
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
