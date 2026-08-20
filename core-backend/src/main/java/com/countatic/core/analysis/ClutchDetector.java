package com.countatic.core.analysis;

import com.countatic.core.entity.MatchEvent;
import com.countatic.core.entity.Player;
import com.countatic.core.entity.Round;
import com.countatic.core.entity.Team;

import java.util.List;

/**
 * Detecta clutch: o jogador ficou como último vivo do seu lado com inimigo em pé.
 *
 * <p>Existe separado da Strategy de Impacto porque agora há dois leitores da
 * mesma regra, e eles precisam de coisas diferentes dela. A métrica quer
 * "tentou / venceu" agregado na partida; o highlight de rodada quer <b>contra
 * quantos</b> — um 1v1 e um 1v4 são o mesmo clutch para a estatística e rounds
 * completamente diferentes para quem lê o destaque.</p>
 *
 * <p>Duas cópias da regra divergiriam: bastaria alguém corrigir a contagem de
 * vivos num lugar. Aqui ela é uma só, e {@code contra} é apenas um detalhe que
 * quem não precisa ignora.</p>
 */
public final class ClutchDetector {

    private ClutchDetector() {
    }

    /**
     * Resultado da leitura de um round.
     *
     * @param tentou  o jogador chegou a ficar sozinho contra alguém
     * @param venceu  sobreviveu e o round foi do lado dele
     * @param contra  inimigos vivos no momento em que ficou sozinho; 0 se não tentou
     */
    public record Clutch(boolean tentou, boolean venceu, int contra) {

        public static final Clutch NENHUM = new Clutch(false, false, 0);
    }

    /**
     * Avalia o round a partir da sequência de kills.
     *
     * <p>Trabalha com o que temos: as kills, em ordem. Assume 5 por lado —
     * verdadeiro em Premier/competitivo, que é o escopo do sistema. Round com
     * desconexão pode gerar falso positivo, e é por isso que a métrica agregada
     * se chama "clutches" e não "1vN".</p>
     *
     * @param kills kills do round <b>já ordenadas por tick</b>
     */
    public static Clutch avaliar(Round round, List<MatchEvent> kills, Long playerId) {
        Team meuLado = descobrirLado(kills, playerId);
        if (meuLado == null) return Clutch.NENHUM;

        int aliadosVivos = 5;
        int inimigosVivos = 5;
        boolean euVivo = true;
        boolean ficouSozinho = false;
        int contra = 0;

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
                // Só o primeiro instante conta: depois disso as kills do
                // próprio clutch derrubariam o número para 1v1 sempre.
                if (!ficouSozinho) contra = inimigosVivos;
                ficouSozinho = true;
            }
        }

        if (!ficouSozinho) return Clutch.NENHUM;

        boolean venceu = euVivo && mesmoLado(round.getWinnerSide(), meuLado);

        return new Clutch(true, venceu, contra);
    }

    /** De que lado o jogador estava, lido dos próprios eventos do round. */
    public static Team descobrirLado(List<MatchEvent> kills, Long playerId) {
        for (MatchEvent e : kills) {
            if (ehAtor(e, playerId) && e.getActorSide() != null) return e.getActorSide();
            if (ehVitima(e, playerId) && e.getVictimSide() != null) return e.getVictimSide();
        }
        return null;
    }

    public static boolean ehAtor(MatchEvent e, Long playerId) {
        return e.getActor() != null && e.getActor().getId().equals(playerId);
    }

    public static boolean ehVitima(MatchEvent e, Long playerId) {
        return e.getVictim() != null && e.getVictim().getId().equals(playerId);
    }

    public static boolean mesmoLado(Team a, Team b) {
        return a != null && b != null && a == b;
    }
}
