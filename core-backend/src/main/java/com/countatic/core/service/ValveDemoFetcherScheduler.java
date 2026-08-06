package com.countatic.core.service;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.countatic.core.dto.stats.MatchAnalysisResult;
import com.countatic.core.entity.Player;
import com.countatic.core.repository.PlayerRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Worker agendado que busca e processa automaticamente novas partidas de CS2
 * para os jogadores cadastrados no sistema.
 *
 * <p>Fluxo automatizado:</p>
 * <ol>
 *   <li>Consulta todos os jogadores com {@code autoFetchEnabled = true}</li>
 *   <li>Para cada jogador, chama a API da Valve ({@link ValveApiService})</li>
 *   <li>Se houver novo Share Code: baixa o {@code .dem.bz2} do CDN e descompacta</li>
 *   <li>Envia os bytes para o Demo Parser Go ({@link DemoParserClientService})</li>
 *   <li>Persiste e calcula estatísticas no Java ({@link MatchAnalysisService})</li>
 *   <li>Atualiza o {@code latestShareCode} do jogador para a próxima iteração</li>
 *   <li>Notifica o jogador via chat da Steam ({@link SteamBotClientService})</li>
 * </ol>
 */
@Slf4j
@Service
public class ValveDemoFetcherScheduler {

    private final PlayerRepository playerRepository;
    private final ValveApiService valveApiService;
    private final DemoParserClientService demoParserClientService;
    private final MatchAnalysisService matchAnalysisService;
    private final SteamBotClientService steamBotClientService;

    public ValveDemoFetcherScheduler(
            PlayerRepository playerRepository,
            ValveApiService valveApiService,
            DemoParserClientService demoParserClientService,
            MatchAnalysisService matchAnalysisService,
            SteamBotClientService steamBotClientService) {
        this.playerRepository = playerRepository;
        this.valveApiService = valveApiService;
        this.demoParserClientService = demoParserClientService;
        this.matchAnalysisService = matchAnalysisService;
        this.steamBotClientService = steamBotClientService;
    }

    /**
     * Executa periodicamente a cada 5 minutos (configurável via {@code steam.auto-fetch-interval-ms}).
     */
    @Scheduled(fixedDelayString = "${steam.auto-fetch-interval-ms:300000}", initialDelay = 10000)
    public void fetchNewMatchesForActivePlayers() {
        log.info("⏰ Executando ciclo de busca automática de partidas de CS2 na Valve...");

        List<Player> eligiblePlayers = playerRepository
                .findByAutoFetchEnabledTrueAndAuthCodeIsNotNullAndLatestShareCodeIsNotNull();

        if (eligiblePlayers.isEmpty()) {
            log.debug("Nenhum jogador ativo cadastrado para auto-fetch.");
            return;
        }

        log.info("Processando auto-fetch para {} jogadores...", eligiblePlayers.size());

        for (Player player : eligiblePlayers) {
            try {
                processNextMatchForPlayer(player);
            } catch (Exception e) {
                log.error("Erro ao processar auto-fetch para o jogador {}: {}",
                        player.getSteamId64(), e.getMessage(), e);
            }
        }
    }

    /**
     * Tenta buscar e processar a próxima partida para um único jogador.
     *
     * @param player jogador alvo
     * @return true se uma nova partida foi encontrada e processada
     */
    public boolean processNextMatchForPlayer(Player player) {
        String steamId64 = player.getSteamId64();
        String authCode = player.getAuthCode();
        String currentShareCode = player.getLatestShareCode();

        player.setLastPolledAt(Instant.now());
        playerRepository.save(player);

        log.debug("Verificando se há novas partidas para {} (Current Code: {})",
                player.getDisplayName(), currentShareCode);

        // 1. Consultar próxima partida na Valve
        String nextShareCode = valveApiService.fetchNextMatchShareCode(steamId64, authCode, currentShareCode);

        if (nextShareCode == null || nextShareCode.equalsIgnoreCase(currentShareCode)) {
            log.debug("Nenhuma nova partida disponível para {}", player.getDisplayName());
            return false;
        }

        log.info("🎮 Nova partida identificada para {}: {}", player.getDisplayName(), nextShareCode);

        // 2. Atualizar o Share Code no banco ANTES de tentar processar a demo
        //    (garante que o sistema avança para a próxima partida mesmo se o download falhar)
        player.setLatestShareCode(nextShareCode);
        playerRepository.save(player);
        log.info("✅ Share Code do jogador {} atualizado para {}", player.getDisplayName(), nextShareCode);

        // 3. Tentar baixar e processar a demo (pode falhar se a URL não estiver disponível)
        try {
            String demoUrl = buildValveDemoUrl(nextShareCode);
            byte[] demoBytes = valveApiService.downloadAndDecompressDemo(demoUrl);
            String fileName = nextShareCode + ".dem";
            String demoHash = calculateSha256(demoBytes);

            // 4. Enviar para o Demo Parser (Go)
            ParsedDemoDTO parsedDemo = demoParserClientService.parseDemo(fileName, demoBytes);

            // 5. Salvar entidades JPA e calcular estatísticas (Spring Strategies)
            MatchAnalysisResult analysisResult = matchAnalysisService.processDemo(fileName, demoHash, parsedDemo);

            // 6. Notificar o jogador via Chat da Steam (Node.js Bot) com stats completas
            steamBotClientService.notifyPlayer(steamId64, analysisResult);

        } catch (Exception e) {
            log.warn("⚠️ Demo não disponível para download ({}). " +
                    "Partida detectada e registrada, mas stats não puderam ser calculadas. Erro: {}",
                    nextShareCode, e.getMessage());

            // Notificar o jogador que uma nova partida foi detectada (sem stats detalhadas)
            try {
                steamBotClientService.sendSimpleNotification(steamId64,
                        String.format("🎮 Nova partida de CS2 detectada!\n" +
                                "📋 Share Code: %s\n" +
                                "⚠️ Demo indisponível para análise automática no momento.",
                                nextShareCode));
            } catch (Exception notifyErr) {
                log.debug("Notificação de partida sem stats falhou: {}", notifyErr.getMessage());
            }
        }

        return true;
    }

    private String buildValveDemoUrl(String shareCode) {
        // Exemplo de URL de CDN da Valve para replays de CS2
        // Em produção, isso vem da chamada `GetFullMatchInfo` da API da Valve.
        return "http://replay137.valorant.net/csgo/" + shareCode + ".dem.bz2";
    }

    private String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(data.hashCode());
        }
    }
}
