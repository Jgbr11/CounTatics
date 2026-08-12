package com.countatic.core.service;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.entity.MatchFetchJob;
import com.countatic.core.entity.MatchFetchStatus;
import com.countatic.core.entity.Player;
import com.countatic.core.repository.MatchFetchJobRepository;
import com.countatic.core.repository.PlayerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converte a torrente de payloads do Game State Integration num único evento
 * de "partida terminou".
 *
 * <p><b>Age na borda, não no estado.</b> O CS2 reenvia o payload a cada ~2 s e
 * a fase {@code gameover} dura todo o placar final. Reagir ao estado criaria
 * dezenas de jobs por partida; reagir à transição cria exatamente um.</p>
 *
 * <p><b>O que o GSI dá e o que não dá.</b> Ele entrega o desempenho do jogador
 * no instante em que a partida acaba — daí o relatório preliminar em ~2 s. Ele
 * <b>não</b> entrega share code, match id nem URL de demo: esses só saem da
 * Valve minutos depois. Por isso o job nasce em {@code AWAITING_SHARECODE} e
 * quem o completa é a sondagem com backoff do {@link MatchJobWorker}.</p>
 *
 * <p><b>A borda pode ser perdida.</b> Se a fase imediatamente anterior ao
 * {@code gameover} não estiver em {@link #FASES_DE_JOGO} (payload perdido,
 * fase desconhecida, etc.), esta transição específica não é detectada. O
 * polling de 5 min não é só rede de segurança para restart do backend — é
 * também o que cobre essa borda perdida.</p>
 */
@Slf4j
@Service
public class GsiEventService {

    /** Fases a partir das quais o {@code gameover} representa fim de partida. */
    private static final Set<String> FASES_DE_JOGO = Set.of("live", "warmup", "intermission");

    private static final String FASE_FINAL = "gameover";

    private final MatchFetchJobService jobService;
    private final MatchFetchJobRepository jobRepository;
    private final PlayerRepository playerRepository;
    private final SteamBotClientService botClient;
    private final ObjectMapper objectMapper;

    /**
     * Última fase vista por jogador.
     *
     * <p>Em memória de propósito: é estado de sessão, não de negócio, e um
     * restart no meio de uma partida apenas faz o gatilho perder aquela
     * transição — o polling de 5 min continua sendo a rede de segurança. O que
     * <i>precisa</i> sobreviver ao restart é a idempotência, e essa vem do
     * banco, não daqui.</p>
     */
    private final Map<String, String> ultimaFasePorJogador = new ConcurrentHashMap<>();

    public GsiEventService(MatchFetchJobService jobService,
                           MatchFetchJobRepository jobRepository,
                           PlayerRepository playerRepository,
                           SteamBotClientService botClient,
                           ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.playerRepository = playerRepository;
        this.botClient = botClient;
        this.objectMapper = objectMapper;
    }

    public void processar(GsiPayloadDTO payload) {
        if (payload == null || payload.getProvider() == null || payload.getMap() == null) {
            return;
        }

        // provider.steamid é o DONO da máquina. player.steamid é quem está
        // sendo observado — ao assistir um companheiro, os dois divergem, e
        // creditar as stats ao observado atribuiria a partida ao jogador errado.
        String steamId = payload.getProvider().getSteamid();
        if (steamId == null || steamId.isBlank()) {
            return;
        }

        String fase = payload.getMap().getPhase();
        // Payload sem fase não carrega transição nenhuma para detectar, e
        // ConcurrentHashMap.put recusa valor nulo — checar antes evita a NPE.
        if (fase == null || fase.isBlank()) {
            return;
        }
        String faseAnterior = ultimaFasePorJogador.put(steamId, fase);

        if (!FASE_FINAL.equals(fase) || faseAnterior == null || !FASES_DE_JOGO.contains(faseAnterior)) {
            return;
        }

        log.info("🏁 GSI: fim de partida detectado para {} ({} → {}) no mapa {}.",
                steamId, faseAnterior, fase, payload.getMap().getName());

        Optional<Player> jogador = playerRepository.findBySteamId64(steamId);
        if (jogador.isEmpty() || !Boolean.TRUE.equals(jogador.get().getAutoFetchEnabled())) {
            log.debug("GSI ignorado: {} não está cadastrado com auto-fetch ativo.", steamId);
            return;
        }

        // Guarda que sobrevive a restart: o mapa em memória some, esta não.
        if (jobRepository.existsBySteamId64AndStatusIn(
                steamId, List.of(MatchFetchStatus.AWAITING_SHARECODE))) {
            log.debug("GSI ignorado: já existe sondagem aberta para {}.", steamId);
            return;
        }

        Integer placarProprio = placarDoLado(payload, true);
        Integer placarAdversario = placarDoLado(payload, false);

        MatchFetchJob job = jobService.enqueueAwaitingShareCode(
                steamId,
                payload.getMap().getName(),
                placarProprio,
                placarAdversario,
                serializarStats(payload));

        enviarPreliminar(steamId, payload, placarProprio, placarAdversario);

        log.info("⚡ Job #{} criado pelo GSI para {} — sondando o share code na Valve.",
                job.getId(), steamId);
    }

    // ═══════════════════════════════════════════════════════════════════

    /**
     * Placar orientado pelo lado do jogador.
     *
     * <p>O GSI reporta {@code team_ct} e {@code team_t} em posições fixas. "13 a
     * 9" só significa vitória depois de saber de que lado o jogador terminou.</p>
     */
    private Integer placarDoLado(GsiPayloadDTO payload, boolean proprio) {
        GsiPayloadDTO.MapState mapa = payload.getMap();
        Integer ct = mapa.getTeamCt() == null ? null : mapa.getTeamCt().getScore();
        Integer t = mapa.getTeamT() == null ? null : mapa.getTeamT().getScore();

        String time = payload.getPlayer() == null ? null : payload.getPlayer().getTeam();
        boolean ehCt = "CT".equalsIgnoreCase(time);

        if (proprio) {
            return ehCt ? ct : t;
        }
        return ehCt ? t : ct;
    }

    private String serializarStats(GsiPayloadDTO payload) {
        if (payload.getPlayer() == null || payload.getPlayer().getMatchStats() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload.getPlayer().getMatchStats());
        } catch (Exception e) {
            // Guardar as stats brutas é conveniência de diagnóstico; falhar aqui
            // não pode custar o gatilho, que é o que realmente importa.
            log.warn("Não foi possível serializar as stats do GSI: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Relatório preliminar: o desempenho do jogador segundos após o fim.
     *
     * <p><b>O {@code @Async} aqui está inerte.</b> Este método é chamado por
     * {@link #processar} na mesma instância, e o proxy do Spring só intercepta
     * chamadas que entram pela borda do bean — auto-invocação passa direto. Na
     * prática a chamada roda de forma síncrona, na thread da requisição HTTP.
     * Quem hoje mantém isso dentro do orçamento de 5 s do GSI não é o
     * {@code @Async}, e sim o fato de ser uma chamada HTTP local rápida ao
     * bot, somado ao try/catch abaixo, que impede uma falha ou lentidão da
     * Steam de propagar e travar o {@code processar}. Se este método crescer
     * e passar a fazer algo mais pesado, ele precisa migrar para um bean
     * próprio — só assim o {@code @Async} passa a valer.</p>
     */
    @Async
    public void enviarPreliminar(String steamId, GsiPayloadDTO payload,
                                 Integer placarProprio, Integer placarAdversario) {
        GsiPayloadDTO.MatchStats s = payload.getPlayer() == null
                ? null : payload.getPlayer().getMatchStats();
        if (s == null) {
            return;
        }

        String resultado;
        if (placarProprio == null || placarAdversario == null) {
            resultado = "";
        } else if (placarProprio > placarAdversario) {
            resultado = " ✅";
        } else if (placarProprio < placarAdversario) {
            resultado = " ❌";
        } else {
            resultado = " 🤝";
        }

        String mensagem = String.format("""
                ⚡ CounTatic — fim de partida detectado

                📍 %s · %s-%s%s
                🔫 K/A/D: %s/%s/%s · ⭐ %s MVPs · 💀 Score %s

                Estou buscando a demo para a análise completa.
                Te mando o link em alguns minutos. 🚀""",
                payload.getMap().getName(),
                placarProprio, placarAdversario, resultado,
                s.getKills(), s.getAssists(), s.getDeaths(), s.getMvps(), s.getScore());

        try {
            botClient.sendSimpleNotification(steamId, mensagem);
        } catch (Exception e) {
            // O relatório preliminar é um bônus. Perdê-lo não pode derrubar o
            // gatilho: o job já está enfileirado e a análise completa segue.
            log.warn("Falha ao enviar o relatório preliminar para {}: {}", steamId, e.getMessage());
        }
    }
}
