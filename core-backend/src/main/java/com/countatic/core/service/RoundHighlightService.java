package com.countatic.core.service;

import com.countatic.core.analysis.ClutchDetector;
import com.countatic.core.dto.stats.RoundHighlightsDTO;
import com.countatic.core.entity.EventType;
import com.countatic.core.entity.Match;
import com.countatic.core.entity.MatchEvent;
import com.countatic.core.entity.Player;
import com.countatic.core.entity.Round;
import com.countatic.core.entity.Team;
import com.countatic.core.repository.MatchRepository;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.repository.RoundRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Elege os melhores rounds do jogador numa partida.
 *
 * <p>Os eventos já vêm agrupados por round desde o parser; o que faltava era
 * uma forma de dizer <b>qual round foi o melhor</b>. Sem uma pontuação,
 * "quatro kills" e "clutch 1v3" não são comparáveis, e escolher o destaque
 * vira palpite.</p>
 *
 * <h2>Os pesos</h2>
 *
 * <p>A unidade é a kill: vale 1,0, e todo o resto é cotado contra ela. Era a
 * decisão de projeto que faltava, e ela é discutível de propósito — está em
 * constantes justamente para ser ajustada depois de ver o resultado em
 * partidas reais.</p>
 *
 * <p>A pergunta que governa a tabela é "um 1v3 vale quantas kills?". Aqui vale
 * <b>quatro</b>, além das próprias kills do clutch: vencer sozinho contra três
 * não é matar três vezes, é ganhar um round que já estava perdido.</p>
 *
 * <p>Dano entra com peso baixo e deliberado. Parte dele já está contada dentro
 * das kills, e o papel dele aqui é outro: não deixar invisível o round em que a
 * pessoa levou três inimigos a 10 de vida e morreu.</p>
 */
@Slf4j
@Service
public class RoundHighlightService {

    /** A unidade da escala. Todo peso abaixo se lê como "quantas kills isto vale". */
    private static final double PESO_KILL = 1.0;

    /** Ganhar o primeiro duelo abre o round em vantagem numérica. */
    private static final double PESO_ABERTURA = 0.6;

    /** Kill que também conserta uma morte do time. */
    private static final double PESO_TRADE = 0.3;

    /** Base do clutch vencido, mais {@link #PESO_CLUTCH_POR_INIMIGO} por adversário além do primeiro. */
    private static final double PESO_CLUTCH_BASE = 2.0;
    private static final double PESO_CLUTCH_POR_INIMIGO = 1.0;

    /** Desarmar decide o round; plantar só cria a condição para decidir. */
    private static final double PESO_DEFUSE = 1.5;
    private static final double PESO_PLANT = 0.5;

    /** 100 de dano vale meia kill — o suficiente para aparecer, longe de dominar. */
    private static final double PESO_DANO = 0.005;

    /** O round que o time levou pesa um pouco mais que o mesmo round perdido. */
    private static final double PESO_ROUND_VENCIDO = 0.5;

    /**
     * Piso para um round ser chamado de destaque.
     *
     * <p>Sem ele, uma partida ruim ainda produziria três "destaques" de uma
     * kill cada — e chamar isso de destaque é mentir para quem lê. Lista vazia
     * é uma resposta honesta.</p>
     */
    private static final double PONTUACAO_MINIMA = 2.5;

    /** Quantos destaques publicar. */
    private static final int QUANTOS = 3;

    /** Mesma janela de trade da Strategy de Impacto. */
    private static final double JANELA_TRADE_SEGUNDOS = 5.0;

    private final MatchRepository matchRepository;
    private final RoundRepository roundRepository;
    private final PlayerRepository playerRepository;

    public RoundHighlightService(MatchRepository matchRepository,
                                 RoundRepository roundRepository,
                                 PlayerRepository playerRepository) {
        this.matchRepository = matchRepository;
        this.roundRepository = roundRepository;
        this.playerRepository = playerRepository;
    }

    @Transactional(readOnly = true)
    public Optional<RoundHighlightsDTO> calcular(Long matchId, String steamId64) {
        Optional<Match> partida = matchRepository.findByIdWithRounds(matchId);
        Optional<Player> jogador = playerRepository.findBySteamId64(steamId64);

        if (partida.isEmpty() || jogador.isEmpty()) {
            return Optional.empty();
        }

        roundRepository.findWithEventsByMatchId(matchId);

        Match m = partida.get();
        Long playerId = jogador.get().getId();

        int tickRate = (m.getTickRate() == null || m.getTickRate() <= 0) ? 64 : m.getTickRate();
        int janelaTicks = (int) (JANELA_TRADE_SEGUNDOS * tickRate);

        List<RoundHighlightsDTO.Destaque> candidatos = new ArrayList<>();

        for (Round round : m.getRounds()) {
            RoundHighlightsDTO.Destaque d = avaliarRound(round, playerId, janelaTicks);
            if (d != null && d.getPontuacao() >= PONTUACAO_MINIMA) {
                candidatos.add(d);
            }
        }

        // Maior pontuação primeiro; empate desempata pelo round mais cedo.
        candidatos.sort(Comparator
                .comparingDouble(RoundHighlightsDTO.Destaque::getPontuacao).reversed()
                .thenComparingInt(RoundHighlightsDTO.Destaque::getRoundNumber));

        return Optional.of(RoundHighlightsDTO.builder()
                .matchId(matchId)
                .steamId64(steamId64)
                .destaques(candidatos.stream().limit(QUANTOS).toList())
                .build());
    }

    // ═══════════════════════════════════════════════════════════════════

    /** Pontua um round; devolve {@code null} quando o jogador não aparece nele. */
    private RoundHighlightsDTO.Destaque avaliarRound(Round round, Long playerId, int janelaTicks) {
        List<MatchEvent> eventos = new ArrayList<>(round.getEvents());
        // Tick nulo em evento malformado: ordenar por ele é pré-requisito de
        // toda a leitura temporal abaixo, igual à Strategy de Impacto.
        eventos.sort(Comparator.comparing(e -> e.getTick() == null ? 0 : e.getTick()));

        List<MatchEvent> kills = eventos.stream()
                .filter(e -> e.getEventType() == EventType.KILL)
                .toList();

        Team meuLado = ClutchDetector.descobrirLado(kills, playerId);

        int minhasKills = 0, headshots = 0, dano = 0, trades = 0;
        boolean plantou = false, desarmou = false;

        for (MatchEvent e : eventos) {
            if (!ClutchDetector.ehAtor(e, playerId)) continue;

            switch (e.getEventType()) {
                case KILL -> {
                    // Fogo amigo não é destaque de rodada.
                    if (fogoAmigo(e)) break;
                    minhasKills++;
                    if (Boolean.TRUE.equals(e.getIsHeadshot())) headshots++;
                    if (ehTrade(e, kills, playerId, janelaTicks)) trades++;
                }
                case DAMAGE -> {
                    if (fogoAmigo(e)) break;
                    if (e.getDamageAmount() != null) dano += e.getDamageAmount();
                }
                case BOMB_PLANTED -> plantou = true;
                case BOMB_DEFUSED -> desarmou = true;
                default -> { /* o resto não descreve o round do jogador */ }
            }
        }

        boolean abertura = !kills.isEmpty()
                && ClutchDetector.ehAtor(kills.get(0), playerId)
                && !fogoAmigo(kills.get(0));

        ClutchDetector.Clutch clutch = ClutchDetector.avaliar(round, kills, playerId);
        boolean venceuRound = ClutchDetector.mesmoLado(round.getWinnerSide(), meuLado);

        // O jogador não aparece neste round.
        if (minhasKills == 0 && dano == 0 && !plantou && !desarmou) {
            return null;
        }

        double pontos = minhasKills * PESO_KILL
                + trades * PESO_TRADE
                + dano * PESO_DANO
                + (abertura ? PESO_ABERTURA : 0)
                + (plantou ? PESO_PLANT : 0)
                + (desarmou ? PESO_DEFUSE : 0)
                + (venceuRound ? PESO_ROUND_VENCIDO : 0);

        if (clutch.venceu()) {
            pontos += PESO_CLUTCH_BASE + (clutch.contra() - 1) * PESO_CLUTCH_POR_INIMIGO;
        }

        RoundHighlightsDTO.Destaque d = RoundHighlightsDTO.Destaque.builder()
                .roundNumber(round.getRoundNumber() == null ? 0 : round.getRoundNumber())
                .pontuacao(round2(pontos))
                .kills(minhasKills)
                .headshots(headshots)
                .damage(dano)
                .tradeKills(trades)
                .clutchContra(clutch.venceu() ? clutch.contra() : 0)
                .clutchVencido(clutch.venceu())
                .abertura(abertura)
                .plantou(plantou)
                .desarmou(desarmou)
                .venceuRound(venceuRound)
                .build();

        d.setTitulo(titulo(d));
        d.setDescricao(descricao(d, meuLado != null));
        return d;
    }

    /**
     * A kill vingou a morte de um aliado nos últimos segundos.
     *
     * <p>Mesma definição da Strategy de Impacto — mas ali a resposta vira uma
     * contagem da partida inteira, e aqui vira uma linha de um round.</p>
     */
    private boolean ehTrade(MatchEvent minhaKill, List<MatchEvent> kills, Long playerId,
                            int janelaTicks) {
        for (MatchEvent anterior : kills) {
            if (anterior == minhaKill) continue;
            if (anterior.getTick() == null || minhaKill.getTick() == null) continue;

            int delta = minhaKill.getTick() - anterior.getTick();
            if (delta < 0 || delta > janelaTicks) continue;

            // Quem morreu antes era do meu lado, e não era eu.
            boolean aliadoMorreu = anterior.getVictim() != null
                    && !anterior.getVictim().getId().equals(playerId)
                    && ClutchDetector.mesmoLado(anterior.getVictimSide(), minhaKill.getActorSide());

            if (aliadoMorreu) return true;
        }
        return false;
    }

    private boolean fogoAmigo(MatchEvent e) {
        return e.getActorSide() != null && e.getVictimSide() != null
                && e.getActorSide() == e.getVictimSide();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TEXTO
    // ═══════════════════════════════════════════════════════════════════

    /** O feito mais raro do round vira o rótulo — não o mais frequente. */
    private String titulo(RoundHighlightsDTO.Destaque d) {
        if (d.isClutchVencido()) return "Clutch 1v" + d.getClutchContra();
        if (d.getKills() >= 4) return d.getKills() + " kills";
        if (d.isDesarmou()) return "Defuse";
        if (d.getKills() >= 2) return d.getKills() + " kills";
        if (d.isAbertura()) return "Abertura";
        if (d.isPlantou()) return "Plant";
        return d.getKills() == 1 ? "1 kill" : d.getDamage() + " de dano";
    }

    private String descricao(RoundHighlightsDTO.Destaque d, boolean ladoConhecido) {
        List<String> partes = new ArrayList<>();

        if (d.getKills() > 0) {
            partes.add(d.getKills() == 1 ? "1 kill" : d.getKills() + " kills");
        }
        if (d.getHeadshots() > 0) {
            partes.add(d.getHeadshots() == 1 ? "1 na cabeça" : d.getHeadshots() + " na cabeça");
        }
        if (d.isAbertura()) partes.add("abriu o round");
        if (d.getTradeKills() > 0) {
            partes.add(d.getTradeKills() == 1 ? "1 troca" : d.getTradeKills() + " trocas");
        }
        if (d.isDesarmou()) partes.add("desarmou a bomba");
        else if (d.isPlantou()) partes.add("plantou a bomba");

        // O dano só é dito quando não veio quase todo de kill: aí ele conta uma
        // coisa que a contagem de kills não conta.
        if (d.getDamage() >= 100 && d.getKills() <= 1) {
            partes.add(d.getDamage() + " de dano");
        }

        if (partes.isEmpty()) partes.add(d.getDamage() + " de dano");

        // Sem nenhuma kill no round não dá para saber de que lado o jogador
        // estava, e "round perdido" seria uma afirmação sem base. Fica calado.
        String fecho;
        if (d.isClutchVencido()) {
            fecho = " Fechou sozinho contra " + porExtenso(d.getClutchContra()) + ".";
        } else if (!ladoConhecido) {
            fecho = "";
        } else {
            fecho = d.isVenceuRound() ? " Round vencido." : " Round perdido.";
        }

        return maiuscula(juntar(partes)) + "." + fecho;
    }

    private String juntar(List<String> partes) {
        if (partes.size() == 1) return partes.get(0);
        return String.join(", ", partes.subList(0, partes.size() - 1))
                + " e " + partes.get(partes.size() - 1);
    }

    private String porExtenso(int n) {
        return switch (n) {
            case 1 -> "um";
            case 2 -> "dois";
            case 3 -> "três";
            case 4 -> "quatro";
            default -> String.valueOf(n);
        };
    }

    private String maiuscula(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
