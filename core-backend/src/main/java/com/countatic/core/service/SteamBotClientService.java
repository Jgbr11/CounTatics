package com.countatic.core.service;

import com.countatic.core.dto.stats.Insight;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.dto.stats.PlayerStatResult;
import com.countatic.core.dto.valve.GCMatchInfoDTO;
import com.countatic.core.exception.GcUnavailableException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.countatic.core.entity.Match;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.HashMap;
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
    private final String webBaseUrl;

    public SteamBotClientService(
            @Qualifier("botRestClient") RestClient botRestClient,
            @Value("${countatic.web-base-url:http://localhost:8080}") String webBaseUrl) {
        this.restClient = botRestClient;
        // Sem barra no fim, para a concatenação da URL ficar previsível.
        this.webBaseUrl = webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                : webBaseUrl;
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
     * <p>Distingue falha transitória de terminal — antes toda exceção virava
     * {@code null} e o scheduler tratava "GC fora do ar" como "partida não existe",
     * descartando a partida permanentemente.</p>
     *
     * @param shareCode        share code da partida (CSGO-xxxxx-...)
     * @param requesterSteamId SteamID64 de quem pediu, para orientar o placar
     * @return informações da partida, ou {@code null} quando a partida realmente
     *         não existe/expirou (404) — caso terminal
     * @throws GcUnavailableException quando o GC está indisponível (503/504) — caso retentável
     */
    public GCMatchInfoDTO.MatchInfo requestMatchInfo(String shareCode, String requesterSteamId) {
        log.info("🔍 Consultando GC para share code: {}", shareCode);

        Map<String, String> body = new HashMap<>();
        body.put("shareCode", shareCode);
        if (requesterSteamId != null) {
            body.put("requesterSteamId", requesterSteamId);
        }

        try {
            GCMatchInfoDTO response = restClient.post()
                    .uri("/match-info")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // 503/504 = GC indisponível -> retentável
                    .onStatus(status -> status == HttpStatus.SERVICE_UNAVAILABLE
                                     || status == HttpStatus.GATEWAY_TIMEOUT,
                            (req, res) -> {
                                throw new GcUnavailableException(
                                        "Game Coordinator indisponível ao consultar " + shareCode
                                                + " (HTTP " + res.getStatusCode().value() + ")");
                            })
                    // 404 = partida inexistente ou fora da retenção -> terminal.
                    // Handler vazio impede o erro padrão; o null é tratado abaixo.
                    .onStatus(status -> status == HttpStatus.NOT_FOUND,
                            (req, res) -> { })
                    .body(GCMatchInfoDTO.class);

            if (response != null && response.isSuccess() && response.getMatchInfo() != null) {
                log.info("✅ Informações da partida recebidas do GC para {}", shareCode);
                return response.getMatchInfo();
            }

            log.warn("GC não retornou informações para {} (partida fora da janela de retenção?)",
                    shareCode);
            return null;
        } catch (GcUnavailableException e) {
            log.warn("⏳ {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // Rede, timeout, DNS: transitório por natureza -> tratar como retentável.
            throw new GcUnavailableException(
                    "Falha de comunicação com o Steam Bot ao consultar " + shareCode
                            + ": " + e.getMessage(), e);
        }
    }

    /**
     * Monta a mensagem curta com o link para a página de detalhes da partida.
     *
     * <p><b>Por que é curta.</b> A Steam limita a frequência de mensagens de
     * chat — enviar o relatório básico e o detalhado em sequência resultava em
     * {@code RateLimitExceeded} e a segunda mensagem se perdia. Além disso, um
     * chat é um péssimo lugar para uma tabela de métricas. Aqui vai só o
     * essencial e um link; o detalhamento fica na página web, onde cabe
     * scoreboard completo, métricas por categoria e as dicas de melhoria.</p>
     */
    public String formatMatchLinkMessage(String steamId64, Match match) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 CounTatic — partida analisada!\n\n");
        sb.append("📍 ").append(match.getMapName());

        Integer ct = match.getScoreCT();
        Integer tr = match.getScoreTR();
        if (ct != null && tr != null) {
            sb.append("  |  ").append(Math.max(ct, tr)).append("-").append(Math.min(ct, tr));
        }
        sb.append("\n");

        if (match.getDurationSeconds() != null && match.getDurationSeconds() > 0) {
            sb.append("⏱️ ").append(match.getDurationSeconds() / 60).append(" min");
            if (match.getTotalRounds() != null) {
                sb.append("  |  ").append(match.getTotalRounds()).append(" rounds");
            }
            sb.append("\n");
        }

        sb.append("\n📊 Relatório completo (mira, utilitárias e dicas):\n");
        sb.append(buildMatchUrl(match));

        return sb.toString();
    }

    /** URL pública da página de detalhes desta partida. */
    public String buildMatchUrl(Match match) {
        // Usa o token e não o id sequencial: a página não tem autenticação,
        // e com id incremental qualquer um varreria as partidas de todos.
        return webBaseUrl + "/m/" + match.getPublicToken();
    }

    /**
     * Formata um relatório de estatísticas básicas do GC para envio via chat da Steam.
     */
    public String formatGCStatsReport(String steamId64, GCMatchInfoDTO.MatchInfo matchInfo) {
        int won = matchInfo.getRoundsWon();
        int lost = matchInfo.getRoundsLost();

        StringBuilder sb = new StringBuilder();
        sb.append("🎯 CounTatic — Relatório de Partida\n\n");

        // O GC do CS2 não informa o mapa (game_mapgroup/game_map vêm null),
        // então a linha é omitida em vez de exibir "unknown".
        if (matchInfo.getMapName() != null && !matchInfo.getMapName().isBlank()) {
            sb.append("📍 Mapa: ").append(matchInfo.getMapName()).append("\n");
        }

        String outcome = won > lost ? "✅ Vitória" : (won < lost ? "❌ Derrota" : "🤝 Empate");
        sb.append("🏁 Resultado: ").append(outcome)
                .append(" (").append(won).append("-").append(lost).append(")\n");

        if (matchInfo.getMatchDuration() > 0) {
            sb.append("⏱️ Duração: ").append(matchInfo.getMatchDuration() / 60).append(" min\n");
        }
        sb.append("\n");

        // Encontrar stats do jogador solicitante
        GCMatchInfoDTO.PlayerStats me = null;
        if (matchInfo.getPlayers() != null) {
            for (GCMatchInfoDTO.PlayerStats player : matchInfo.getPlayers()) {
                if (steamId64.equals(player.getSteamId64())) {
                    me = player;
                    break;
                }
            }
        }

        if (me != null) {
            sb.append("📊 Suas Estatísticas:\n");
            sb.append(formatPlayerLine(me));
        } else {
            // Antes o bloco inteiro sumia em silêncio e o jogador recebia só o
            // placar, sem nenhuma pista do motivo. Agora avisa e manda o
            // scoreboard completo, que ainda é útil.
            log.warn("SteamID {} não encontrado entre os {} jogadores retornados pelo GC. "
                            + "IDs recebidos: {}",
                    steamId64,
                    matchInfo.getPlayers() == null ? 0 : matchInfo.getPlayers().size(),
                    matchInfo.getPlayers() == null ? "[]"
                            : matchInfo.getPlayers().stream()
                                    .map(GCMatchInfoDTO.PlayerStats::getSteamId64).toList());

            sb.append("⚠️ Não consegui isolar as suas estatísticas nesta partida.\n");
            sb.append("   Confira se o SteamID cadastrado é o mesmo com que você jogou.\n\n");

            if (matchInfo.getPlayers() != null && !matchInfo.getPlayers().isEmpty()) {
                sb.append("📊 Placar completo:\n");
                for (GCMatchInfoDTO.PlayerStats p : matchInfo.getPlayers()) {
                    sb.append("  • ").append(p.getPlayerName()).append(" — ")
                            .append(p.getKills()).append("/")
                            .append(p.getDeaths()).append("/")
                            .append(p.getAssists()).append("\n");
                }
            }
        }

        sb.append("\nGLHF na próxima! 🚀");
        return sb.toString();
    }

    /** Bloco de estatísticas individuais usado no relatório do GC. */
    private String formatPlayerLine(GCMatchInfoDTO.PlayerStats player) {
        double hsPercent = player.getKills() > 0
                ? (player.getHeadshots() * 100.0 / player.getKills())
                : 0;

        return "  🔫 K/D/A: " + player.getKills() + "/" + player.getDeaths() + "/"
                + player.getAssists() + "\n"
                + "  🎯 Headshots: " + player.getHeadshots()
                + String.format(" (%.1f%%)", hsPercent) + "\n"
                + "  ⭐ MVPs: " + player.getMvps() + "\n"
                + "  💀 Score: " + player.getScore() + "\n";
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
                        // Ordena por gravidade: o que exige ação primeiro. Numa
                        // mensagem de chat o jogador lê as primeiras linhas e
                        // rola o resto, então a ordem é o que decide se o alerta
                        // chega. O símbolo vem da gravidade, não do texto.
                        stat.getInsights().values().stream()
                                .sorted(Comparator.comparing(Insight::gravidade))
                                .forEach(i -> sb.append("  ")
                                        .append(simbolo(i.gravidade()))
                                        .append(" ")
                                        .append(i.texto())
                                        .append("\n"));
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("GLHF na próxima partida! 🚀");
        return sb.toString();
    }

    /**
     * Símbolo do insight no chat da Steam, escolhido pela gravidade.
     *
     * <p>Fica aqui e não no texto do insight de propósito: a mesma dica vai
     * para a página web, que usa ícone SVG. Emoji embutido na mensagem
     * apareceria duplicado lá.</p>
     */
    private static String simbolo(Insight.Severidade gravidade) {
        return switch (gravidade) {
            case AVISO -> "⚠️";
            case SUCESSO -> "✅";
            case INFO -> "👉";
        };
    }
}
