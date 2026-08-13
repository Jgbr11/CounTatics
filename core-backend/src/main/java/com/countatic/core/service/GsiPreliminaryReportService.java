package com.countatic.core.service;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Envia ao jogador o relatório preliminar — o desempenho dele segundos após o
 * fim da partida, antes de a demo existir.
 *
 * <p><b>Por que isto é um bean separado.</b> Não é organização de código: é o
 * que faz o {@code @Async} existir de verdade. O proxy do Spring só intercepta
 * chamadas que entram pela <i>borda</i> do bean; quando este método vivia
 * dentro do {@link GsiEventService} e era chamado por {@code processar} na
 * mesma instância, a auto-invocação passava direto pelo proxy e o
 * {@code @Async} ficava inerte — o envio rodava síncrono, na thread do Tomcat
 * que atende {@code POST /api/gsi}.</p>
 *
 * <p><b>O número que torna isso perigoso.</b> {@code sendSimpleNotification}
 * usa o {@code botRestClient} de {@code RestClientConfig}, cujo read timeout é
 * de <b>90 segundos</b> — calibrado assim de propósito, porque o {@code /notify}
 * do bot leva até ~65 s quando a Steam aplica rate limit e ele faz backoff de
 * 5 s + 15 s + 45 s, algo que acontece de forma rotineira. O CS2, do outro
 * lado, dá <b>5 segundos</b> de orçamento por requisição do GSI
 * ({@code "timeout" "5.0"} no {@code .cfg}) e marca o endpoint como morto se
 * estourar. Sem a fronteira de bean, um rate limit da Steam prenderia a thread
 * do Tomcat por até 18× o orçamento do CS2 e o gatilho inteiro se desligaria
 * sozinho — justamente no dia em que a Steam está ruim.</p>
 *
 * <p><b>A assimetria que justifica engolir a falha.</b> Perder o preliminar é
 * aceitável: o job já está enfileirado e a análise completa segue seu curso
 * pelo worker. Travar a resposta ao CS2 não é — isso custa todas as partidas
 * seguintes, não só esta. Daí o try/catch abaixo, que absorve a falha com
 * {@code log.warn} em vez de deixá-la propagar.</p>
 *
 * <p>Depende de {@code @EnableAsync} em {@code CoreBackendApplication}: sem
 * ele, o proxy não é criado e a chamada volta a ser síncrona.</p>
 */
@Slf4j
@Service
public class GsiPreliminaryReportService {

    private final SteamBotClientService botClient;

    public GsiPreliminaryReportService(SteamBotClientService botClient) {
        this.botClient = botClient;
    }

    @Async
    public void enviar(String steamId, GsiPayloadDTO payload,
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
