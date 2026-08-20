package com.countatic.core.service;

import com.countatic.core.entity.PlayerMatchStats;
import com.countatic.core.repository.PlayerMatchStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Recordes pessoais: onde a partida atual superou tudo o que o jogador já fez
 * <b>naquele mapa</b>.
 *
 * <p>Por mapa, e não no geral, porque é assim que o jogador pensa. Um ADR de
 * 95 na Nuke pode ser recorde pessoal e estar abaixo da média dele na Mirage —
 * juntar os dois esconderia as duas informações.</p>
 *
 * <p><b>Calculado na leitura, não gravado.</b> Um campo persistido responderia
 * "isto era recorde quando foi analisado", que envelhece: a partida seguinte
 * bate a marca e a anterior continua marcada. Aqui a resposta é sempre "esta
 * ainda é a sua melhor no mapa", que é o que o ícone promete. De quebra,
 * reprocessar uma partida não deixa marca velha para trás.</p>
 */
@Slf4j
@Service
public class PersonalRecordService {

    /**
     * Partidas anteriores no mapa exigidas para que um recorde seja anunciado.
     *
     * <p>Sem isso, a primeira partida num mapa seria recorde em tudo — e a
     * segunda, em metade. Anunciar recorde quando não há com o que comparar
     * transforma o ícone em enfeite e ele deixa de ser lido.</p>
     */
    @Value("${countatic.records.min-historico:3}")
    private int minimoHistorico;

    /**
     * Como ler cada métrica e para que lado ela é melhor.
     *
     * <p>A direção vem do {@link BaselineService}, a mesma fonte que o
     * percentil usa. Em {@code deathsPerRound} e {@code earlyDeathRate} o
     * recorde é o <b>mínimo</b>; tratar tudo como máximo premiaria quem mais
     * morre.</p>
     */
    private static final Map<String, Function<PlayerMatchStats, Double>> LEITORES =
            PlayerTrendService.leitores();

    private final PlayerMatchStatsRepository statsRepository;

    public PersonalRecordService(PlayerMatchStatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    /**
     * Recordes de cada jogador da partida.
     *
     * @param mapa           mapa da partida atual
     * @param matchIdAtual   excluído da comparação — senão a partida seria o
     *                       próprio recorde a bater
     * @param atuais         desempenho desta partida, por steamId64
     * @return chaves de métrica em que cada jogador bateu o próprio recorde
     */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> recordes(String mapa, Long matchIdAtual,
                                             Map<String, Map<String, Double>> atuais) {
        Map<String, Set<String>> saida = new LinkedHashMap<>();
        if (mapa == null || matchIdAtual == null || atuais == null || atuais.isEmpty()) {
            return saida;
        }

        List<String> ids = List.copyOf(atuais.keySet());
        List<PlayerMatchStats> anteriores =
                statsRepository.findAnterioresNoMapa(ids, mapa, matchIdAtual);

        // Agrupa o histórico por jogador antes de comparar: cada um tem o
        // próprio recorde e a própria contagem de partidas no mapa.
        Map<String, List<PlayerMatchStats>> porJogador = new HashMap<>();
        for (PlayerMatchStats s : anteriores) {
            porJogador.computeIfAbsent(s.getSteamId64(), k -> new java.util.ArrayList<>()).add(s);
        }

        for (Map.Entry<String, Map<String, Double>> e : atuais.entrySet()) {
            List<PlayerMatchStats> historico = porJogador.get(e.getKey());
            if (historico == null || historico.size() < minimoHistorico) {
                continue;
            }
            Set<String> batidos = comparar(e.getValue(), historico);
            if (!batidos.isEmpty()) {
                saida.put(e.getKey(), batidos);
            }
        }

        return saida;
    }

    private Set<String> comparar(Map<String, Double> atual, List<PlayerMatchStats> historico) {
        Set<String> batidos = new HashSet<>();

        for (Map.Entry<String, Function<PlayerMatchStats, Double>> m : LEITORES.entrySet()) {
            String chave = m.getKey();

            Double valorAtual = atual.get(chave);
            // Métrica não medida nesta partida não pode ser recorde. É a mesma
            // regra de sempre: ausência não é zero, e aqui zero venceria o
            // recorde de toda métrica em que menor é melhor.
            if (valorAtual == null) continue;

            boolean maiorEhMelhor = BaselineService.descrever(chave)
                    .map(BaselineService.MetricaDescricao::maiorEhMelhor)
                    .orElse(true);

            Double melhorAnterior = null;
            for (PlayerMatchStats s : historico) {
                Double v = m.getValue().apply(s);
                if (v == null) continue;
                if (melhorAnterior == null
                        || (maiorEhMelhor ? v > melhorAnterior : v < melhorAnterior)) {
                    melhorAnterior = v;
                }
            }

            // Sem nenhuma medição anterior desta métrica não há recorde a
            // bater — mesmo que existam partidas no mapa.
            if (melhorAnterior == null) continue;

            // Empatar não é bater: repetir a própria marca não é recorde novo.
            if (maiorEhMelhor ? valorAtual > melhorAnterior : valorAtual < melhorAnterior) {
                batidos.add(chave);
            }
        }

        return batidos;
    }
}
