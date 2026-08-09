package com.countatic.core.strategy.impl;

import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.entity.*;
import com.countatic.core.strategy.StatCalculationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Strategy para métricas de <b>impacto</b> — o que o jogador efetivamente
 * mudou no resultado dos rounds.
 *
 * <p>Métricas:</p>
 * <ul>
 *   <li><b>ADR</b> (dano médio por round) — a métrica mais pedida e a que
 *       melhor captura contribuição de quem não finaliza os duelos.</li>
 *   <li><b>Trade kills</b> — quantas vezes vingou a morte de um aliado, e
 *       quantas vezes sua própria morte foi vingada.</li>
 *   <li><b>Opening duels</b> — o primeiro duelo do round, o de maior impacto
 *       no resultado: com que frequência participa e com que taxa vence.</li>
 *   <li><b>Clutches</b> — rounds vencidos estando sozinho contra N inimigos.</li>
 * </ul>
 *
 * <p>Diferente das outras strategies, esta <b>não achata os eventos</b> da
 * partida: quase tudo aqui depende de contexto de round (quem morreu antes de
 * quem, quantos estavam vivos) e de janelas temporais, que só fazem sentido
 * dentro de um mesmo round.</p>
 */
@Slf4j
@Component
public class ImpactStatStrategy implements StatCalculationStrategy {

    private static final String CATEGORY = "Impacto";

    /**
     * Janela para considerar uma kill como troca (trade).
     *
     * <p>5 segundos é a convenção do cenário competitivo: acima disso a morte
     * do aliado já não é a causa do duelo, e a kill vira um evento separado.</p>
     */
    private static final double TRADE_WINDOW_SECONDS = 5.0;

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public PlayerStatResult calculate(Match match, Player player) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        Map<String, String> insights = new LinkedHashMap<>();

        Long playerId = player.getId();
        int totalRounds = match.getTotalRounds() == null ? 0 : match.getTotalRounds();

        // Converte a janela de trade de segundos para ticks usando o tick rate
        // real da partida. Fixar em ticks quebraria em servidores 128 tick.
        int tickRate = (match.getTickRate() == null || match.getTickRate() <= 0)
                ? 64 : match.getTickRate();
        int tradeWindowTicks = (int) (TRADE_WINDOW_SECONDS * tickRate);

        int totalDamage = 0;
        int tradeKills = 0;
        int tradedDeaths = 0;
        int openingsTaken = 0;
        int openingsWon = 0;
        int clutchesWon = 0;
        int clutchesAttempted = 0;

        for (Round round : match.getRounds()) {
            List<MatchEvent> eventos = new ArrayList<>(round.getEvents());
            // O tick pode vir nulo em eventos malformados; ordenar por ele é
            // pré-requisito de toda a lógica temporal abaixo.
            eventos.sort(Comparator.comparing(e -> e.getTick() == null ? 0 : e.getTick()));

            totalDamage += somarDano(eventos, playerId);

            List<MatchEvent> kills = eventos.stream()
                    .filter(e -> e.getEventType() == EventType.KILL)
                    .toList();

            if (kills.isEmpty()) continue;

            tradeKills += contarTradeKills(kills, playerId, tradeWindowTicks);
            tradedDeaths += contarMortesVingadas(kills, playerId, tradeWindowTicks);

            // ─── Opening duel: a PRIMEIRA kill do round ──────────────────
            MatchEvent primeira = kills.get(0);
            boolean matou = ehAtor(primeira, playerId);
            boolean morreu = ehVitima(primeira, playerId);

            if (matou || morreu) {
                openingsTaken++;
                if (matou) openingsWon++;
            }

            // ─── Clutch ──────────────────────────────────────────────────
            ClutchResult clutch = avaliarClutch(round, kills, playerId);
            if (clutch.tentou()) {
                clutchesAttempted++;
                if (clutch.venceu()) clutchesWon++;
            }
        }

        // ─── ADR ─────────────────────────────────────────────────────────
        double adr = totalRounds > 0 ? (double) totalDamage / totalRounds : 0.0;
        metrics.put("adr", round2(adr));
        metrics.put("totalDamage", (double) totalDamage);
        insights.put("adr", insightAdr(adr));

        // ─── Trades ──────────────────────────────────────────────────────
        metrics.put("tradeKills", (double) tradeKills);
        metrics.put("tradedDeaths", (double) tradedDeaths);
        if (tradeKills + tradedDeaths > 0) {
            insights.put("trades", insightTrades(tradeKills, tradedDeaths, totalRounds));
        }

        // ─── Opening duels ───────────────────────────────────────────────
        metrics.put("openingDuels", (double) openingsTaken);
        metrics.put("openingDuelsWon", (double) openingsWon);
        if (openingsTaken > 0) {
            double taxa = (openingsWon * 100.0) / openingsTaken;
            metrics.put("openingDuelWinRate", round2(taxa));
            insights.put("openingDuels", insightOpening(taxa, openingsTaken, totalRounds));
        }

        // ─── Clutches ────────────────────────────────────────────────────
        metrics.put("clutchesWon", (double) clutchesWon);
        metrics.put("clutchesAttempted", (double) clutchesAttempted);
        if (clutchesAttempted > 0) {
            metrics.put("clutchWinRate", round2((clutchesWon * 100.0) / clutchesAttempted));
            insights.put("clutches", insightClutch(clutchesWon, clutchesAttempted));
        }

        return PlayerStatResult.builder()
                .category(CATEGORY)
                .steamId64(player.getSteamId64())
                .playerName(player.getDisplayName())
                .metrics(metrics)
                .insights(insights)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CÁLCULOS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Soma o dano causado pelo jogador no round.
     *
     * <p>Ignora fogo amigo: dano em aliado não é contribuição. O parser Go
     * registra os dois, e a decisão de filtrar fica aqui.</p>
     */
    private int somarDano(List<MatchEvent> eventos, Long playerId) {
        int soma = 0;
        for (MatchEvent e : eventos) {
            if (e.getEventType() != EventType.DAMAGE) continue;
            if (!ehAtor(e, playerId)) continue;
            if (e.getDamageAmount() == null) continue;
            if (mesmoTime(e)) continue;
            soma += e.getDamageAmount();
        }
        return soma;
    }

    /**
     * Trade kill: o jogador matou alguém que, há pouco, tinha matado um aliado dele.
     */
    private int contarTradeKills(List<MatchEvent> kills, Long playerId, int janelaTicks) {
        int total = 0;

        for (int i = 0; i < kills.size(); i++) {
            MatchEvent minhaKill = kills.get(i);
            if (!ehAtor(minhaKill, playerId)) continue;

            Player vitima = minhaKill.getVictim();
            if (vitima == null) continue;

            // Essa vítima matou um aliado meu dentro da janela?
            for (int j = 0; j < i; j++) {
                MatchEvent anterior = kills.get(j);
                if (!dentroDaJanela(anterior, minhaKill, janelaTicks)) continue;

                boolean vitimaFoiOAlgoz = anterior.getActor() != null
                        && anterior.getActor().getId().equals(vitima.getId());
                boolean aliadoMeuMorreu = anterior.getVictim() != null
                        && !anterior.getVictim().getId().equals(playerId)
                        && mesmoLado(anterior.getVictimSide(), minhaKill.getActorSide());

                if (vitimaFoiOAlgoz && aliadoMeuMorreu) {
                    total++;
                    break; // uma kill vale uma troca, não várias
                }
            }
        }

        return total;
    }

    /**
     * Morte vingada: o jogador morreu e um aliado matou o algoz logo em seguida.
     *
     * <p>Complementa o trade kill: mede se o time cobre as suas mortes — morrer
     * sendo trocado custa muito menos ao round do que morrer de graça.</p>
     */
    private int contarMortesVingadas(List<MatchEvent> kills, Long playerId, int janelaTicks) {
        int total = 0;

        for (int i = 0; i < kills.size(); i++) {
            MatchEvent minhaMorte = kills.get(i);
            if (!ehVitima(minhaMorte, playerId)) continue;

            Player algoz = minhaMorte.getActor();
            if (algoz == null) continue;

            for (int j = i + 1; j < kills.size(); j++) {
                MatchEvent posterior = kills.get(j);
                if (!dentroDaJanela(minhaMorte, posterior, janelaTicks)) break;

                boolean algozMorreu = posterior.getVictim() != null
                        && posterior.getVictim().getId().equals(algoz.getId());
                boolean porAliadoMeu = posterior.getActor() != null
                        && !posterior.getActor().getId().equals(playerId)
                        && mesmoLado(posterior.getActorSide(), minhaMorte.getVictimSide());

                if (algozMorreu && porAliadoMeu) {
                    total++;
                    break;
                }
            }
        }

        return total;
    }

    private record ClutchResult(boolean tentou, boolean venceu) {
    }

    /**
     * Detecta situação de clutch: o jogador ficou como último vivo do seu lado
     * e ainda havia inimigo em pé.
     *
     * <p>Trabalha com o que temos: a sequência de kills do round. Assume 5 por
     * lado — verdadeiro em Premier/competitivo, que é o escopo do sistema.
     * Rounds com desconexão podem gerar falso positivo; por isso a métrica é
     * apresentada como "clutches" e não como número exato de 1vN.</p>
     */
    private ClutchResult avaliarClutch(Round round, List<MatchEvent> kills, Long playerId) {
        Team meuLado = descobrirLado(kills, playerId);
        if (meuLado == null) return new ClutchResult(false, false);

        int aliadosVivos = 5;
        int inimigosVivos = 5;
        boolean euVivo = true;
        boolean ficouSozinho = false;

        for (MatchEvent kill : kills) {
            Player vitima = kill.getVictim();
            if (vitima == null) continue;

            if (vitima.getId().equals(playerId)) {
                euVivo = false;
                aliadosVivos--;
            } else if (mesmoLado(kill.getVictimSide(), meuLado)) {
                aliadosVivos--;
            } else {
                inimigosVivos--;
            }

            // Último do meu lado, com inimigo ainda em pé.
            if (euVivo && aliadosVivos == 1 && inimigosVivos > 0) {
                ficouSozinho = true;
            }
        }

        if (!ficouSozinho) return new ClutchResult(false, false);

        boolean venceu = euVivo && mesmoLado(round.getWinnerSide(), meuLado);

        return new ClutchResult(true, venceu);
    }

    /** Descobre de que lado o jogador estava, a partir dos eventos do round. */
    private Team descobrirLado(List<MatchEvent> kills, Long playerId) {
        for (MatchEvent e : kills) {
            if (ehAtor(e, playerId) && e.getActorSide() != null) return e.getActorSide();
            if (ehVitima(e, playerId) && e.getVictimSide() != null) return e.getVictimSide();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INSIGHTS
    // ═══════════════════════════════════════════════════════════════════

    private String insightAdr(double adr) {
        if (adr >= 90) {
            return String.format("ADR excelente (%.0f). Você está causando dano consistente todo round.", adr);
        }
        if (adr >= 70) {
            return String.format("ADR bom (%.0f). Acima da média — continue buscando os primeiros contatos.", adr);
        }
        if (adr >= 50) {
            return String.format("ADR mediano (%.0f). Tente abrir mais duelos e usar HE em posições prováveis.", adr);
        }
        return String.format("ADR baixo (%.0f). Mesmo sem finalizar, causar dano ajuda o time — "
                + "priorize trocar tiro em vez de esperar o round decidir sozinho.", adr);
    }

    private String insightTrades(int tradeKills, int tradedDeaths, int rounds) {
        if (rounds <= 0) return "";

        if (tradeKills >= tradedDeaths && tradeKills > 0) {
            return String.format("Você vingou %d morte(s) de aliado e teve %d das suas vingadas. "
                    + "Bom jogo de cobertura.", tradeKills, tradedDeaths);
        }
        return String.format("Você vingou %d morte(s) de aliado, mas %d das suas foram vingadas. "
                + "Ficar mais perto do time aumenta a chance de trocar as mortes.", tradeKills, tradedDeaths);
    }

    private String insightOpening(double taxa, int duelos, int rounds) {
        if (rounds > 0 && duelos * 100.0 / rounds < 20) {
            return String.format("Você participou do primeiro duelo em poucos rounds (%d de %d). "
                    + "Não é erro — mas quem abre o round costuma decidi-lo.", duelos, rounds);
        }
        if (taxa >= 60) {
            return String.format("Você vence %.0f%% dos primeiros duelos. Isso abre rounds para o time.", taxa);
        }
        if (taxa >= 45) {
            return String.format("Você vence %.0f%% dos primeiros duelos — equilibrado.", taxa);
        }
        return String.format("Você perde a maioria dos primeiros duelos (%.0f%% de vitória). "
                + "Tente entrar com utilitária ou com um aliado pronto para trocar.", taxa);
    }

    private String insightClutch(int vencidos, int tentados) {
        if (vencidos == 0) {
            return String.format("Você ficou sozinho em %d round(s) e não converteu. "
                    + "Nessas horas, jogar pelo tempo e isolar duelos costuma render mais.", tentados);
        }
        return String.format("Você venceu %d de %d situação(ões) sozinho contra o time adversário.",
                vencidos, tentados);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UTILITÁRIOS
    // ═══════════════════════════════════════════════════════════════════

    private boolean ehAtor(MatchEvent e, Long playerId) {
        return e.getActor() != null && e.getActor().getId().equals(playerId);
    }

    private boolean ehVitima(MatchEvent e, Long playerId) {
        return e.getVictim() != null && e.getVictim().getId().equals(playerId);
    }

    /** Fogo amigo: ator e vítima no mesmo lado. */
    private boolean mesmoTime(MatchEvent e) {
        return e.getActorSide() != null
                && e.getVictimSide() != null
                && e.getActorSide().equals(e.getVictimSide());
    }

    private boolean mesmoLado(Team a, Team b) {
        return a != null && b != null && a == b;
    }

    private boolean dentroDaJanela(MatchEvent antes, MatchEvent depois, int janelaTicks) {
        if (antes.getTick() == null || depois.getTick() == null) return false;
        int delta = depois.getTick() - antes.getTick();
        return delta >= 0 && delta <= janelaTicks;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
