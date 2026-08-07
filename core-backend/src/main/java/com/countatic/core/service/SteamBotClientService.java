package com.countatic.core.service;

import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.dto.valve.GCMatchInfoDTO;
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
     * Consulta o Game Coordinator do CS2 via Steam Bot para obter estatísticas de uma partida.
     *
     * @param shareCode share code da partida (CSGO-xxxxx-...)
     * @return informações da partida ou null se não disponível
     */
    public GCMatchInfoDTO.MatchInfo requestMatchInfo(String shareCode) {
        log.info("🔍 Consultando GC para share code: {}", shareCode);

        try {
            GCMatchInfoDTO response = restClient.post()
                    .uri("/match-info")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("shareCode", shareCode))
                    .retrieve()
                    .body(GCMatchInfoDTO.class);

            if (response != null && response.isSuccess() && response.getMatchInfo() != null) {
                log.info("✅ Informações da partida recebidas do GC para {}", shareCode);
                return response.getMatchInfo();
            }

            log.warn("GC não retornou informações para {}", shareCode);
            return null;
        } catch (Exception e) {
            log.error("Erro ao consultar GC para {}: {}", shareCode, e.getMessage());
            return null;
        }
    }

    /**
     * Formata um relatório de estatísticas básicas do GC para envio via chat da Steam.
     */
    public String formatGCStatsReport(String steamId64, GCMatchInfoDTO.MatchInfo matchInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 CounTatic — Relatório de Partida\n\n");
        sb.append("📍 Mapa: ").append(matchInfo.getMapName());
        sb.append(" | Placar: ").append(matchInfo.getRoundsWon()).append("-").append(matchInfo.getRoundsLost());
        sb.append("\n");

        if (matchInfo.getMatchDuration() > 0) {
            sb.append("⏱️ Duração: ").append(matchInfo.getMatchDuration() / 60).append(" min\n");
        }
        sb.append("\n");

        // Encontrar stats do jogador solicitante
        if (matchInfo.getPlayers() != null) {
            for (GCMatchInfoDTO.PlayerStats player : matchInfo.getPlayers()) {
                if (steamId64.equals(player.getSteamId64())) {
                    double hsPercent = player.getKills() > 0
                            ? (player.getHeadshots() * 100.0 / player.getKills())
                            : 0;

                    sb.append("📊 Suas Estatísticas:\n");
                    sb.append("  🔫 K/D/A: ").append(player.getKills()).append("/")
                            .append(player.getDeaths()).append("/")
                            .append(player.getAssists()).append("\n");
                    sb.append("  🎯 Headshots: ").append(player.getHeadshots())
                            .append(String.format(" (%.1f%%)", hsPercent)).append("\n");
                    sb.append("  ⭐ MVPs: ").append(player.getMvps()).append("\n");
                    sb.append("  💀 Score: ").append(player.getScore()).append("\n");
                    break;
                }
            }
        }

        sb.append("\nGLHF na próxima! 🚀");
        return sb.toString();
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
