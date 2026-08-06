package com.countatic.core.service;

import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.dto.stats.PlayerStatResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Cliente HTTP responsável por enviar notificações para o microsserviço Steam Bot (Node.js).
 *
 * <p>Formata o resultado da análise de estatísticas em uma mensagem de chat legível
 * e agradável para o jogador, enviando via {@code POST /notify}.</p>
 */
@Slf4j
@Service
public class SteamBotClientService {

    private final RestClient restClient;

    public SteamBotClientService(
            @Value("${services.bot-url:http://localhost:3000}") String botUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(botUrl)
                .build();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BotNotifyPayload {
        private String steamId;
        private String message;
        private Long matchId;
        private String playerName;
    }

    /**
     * Envia o relatório de estatísticas de uma partida para o chat da Steam de um jogador.
     *
     * @param steamId64    SteamID64 do jogador
     * @param matchResult  resultado completo da análise da partida
     * @return true se o envio foi bem-sucedido
     */
    public boolean notifyPlayer(String steamId64, MatchAnalysisResult matchResult) {
        String formattedMessage = formatReportMessage(steamId64, matchResult);

        BotNotifyPayload payload = BotNotifyPayload.builder()
                .steamId(steamId64)
                .message(formattedMessage)
                .matchId(matchResult.getMatchId())
                .build();

        log.info("📤 Enviando relatório para o Steam Bot (jogador: {}, matchId: {})...",
                steamId64, matchResult.getMatchId());

        try {
            restClient.post()
                    .uri("/notify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("✅ Notificação entregue com sucesso ao Steam Bot para {}", steamId64);
            return true;
        } catch (Exception e) {
            log.error("Erro ao enviar notificação para o Steam Bot para {}: {}",
                    steamId64, e.getMessage());
            return false;
        }
    }

    /**
     * Envia uma mensagem simples de texto para o chat da Steam de um jogador.
     *
     * @param steamId64 SteamID64 do jogador
     * @param message   mensagem de texto
     * @return true se o envio foi bem-sucedido
     */
    public boolean sendSimpleNotification(String steamId64, String message) {
        BotNotifyPayload payload = BotNotifyPayload.builder()
                .steamId(steamId64)
                .message(message)
                .build();

        log.info("📤 Enviando notificação simples para {} via Steam Bot...", steamId64);

        try {
            restClient.post()
                    .uri("/notify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("✅ Notificação simples entregue ao Steam Bot para {}", steamId64);
            return true;
        } catch (Exception e) {
            log.error("Erro ao enviar notificação simples para {}: {}", steamId64, e.getMessage());
            return false;
        }
    }

    /**
     * Formata o resultado da análise estatística em uma mensagem legível para o chat da Steam.
     */
    private String formatReportMessage(String steamId64, MatchAnalysisResult matchResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 *CounTatic Analysis Report*\n");
        sb.append("📍 Mapa: ").append(matchResult.getMapName());
        sb.append(" | Placar: ").append(matchResult.getFinalScore()).append("\n\n");

        if (matchResult.getPlayerStats() != null) {
            for (PlayerStatResult stat : matchResult.getPlayerStats()) {
                if (steamId64.equals(stat.getSteamId64())) {
                    sb.append("📊 *Métricas (").append(stat.getCategory()).append(")*:\n");
                    if (stat.getMetrics() != null) {
                        for (Map.Entry<String, Double> m : stat.getMetrics().entrySet()) {
                            sb.append("  • ").append(m.getKey()).append(": ").append(m.getValue()).append("\n");
                        }
                    }
                    if (stat.getInsights() != null && !stat.getInsights().isEmpty()) {
                        sb.append("\n💡 *Dicas de Melhoria*:\n");
                        for (String insight : stat.getInsights().values()) {
                            sb.append("  👉 ").append(insight).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("GLHF na próxima partida! 🚀");
        return sb.toString();
    }
}
