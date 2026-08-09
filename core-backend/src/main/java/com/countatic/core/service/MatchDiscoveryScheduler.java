package com.countatic.core.service;

import com.countatic.core.entity.FetchSource;
import com.countatic.core.entity.Player;
import com.countatic.core.exception.ValveAuthException;
import com.countatic.core.exception.ValveTransientException;
import com.countatic.core.repository.PlayerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Descobre novas partidas consultando a API da Valve e as enfileira como
 * {@link com.countatic.core.entity.MatchFetchJob}.
 *
 * <p>Esta classe <b>só descobre</b>. Todo o processamento pesado (Game
 * Coordinator, download, parsing, notificação) fica no {@link MatchJobWorker}.
 * A separação existe porque as duas coisas têm cadências e modos de falha
 * completamente diferentes: descobrir é uma chamada HTTP de 1 s a cada 5 min;
 * processar leva minutos e falha de dez maneiras distintas.</p>
 *
 * <p>Continua servindo como <b>rede de segurança</b> depois que o gatilho por
 * GSI entrar: se você jogar em outra máquina, sem o {@code .cfg}, ou o CS2
 * fechar antes do fim da partida, é este ciclo que encontra a partida.</p>
 */
@Slf4j
@Service
public class MatchDiscoveryScheduler {

    private final PlayerRepository playerRepository;
    private final ValveApiService valveApiService;
    private final MatchFetchJobService jobService;
    private final SteamBotClientService steamBotClientService;

    public MatchDiscoveryScheduler(PlayerRepository playerRepository,
                                    ValveApiService valveApiService,
                                    MatchFetchJobService jobService,
                                    SteamBotClientService steamBotClientService) {
        this.playerRepository = playerRepository;
        this.valveApiService = valveApiService;
        this.jobService = jobService;
        this.steamBotClientService = steamBotClientService;
    }

    public ValveApiService getValveApiService() {
        return valveApiService;
    }

    @Scheduled(fixedDelayString = "${steam.auto-fetch-interval-ms:300000}", initialDelay = 10000)
    public void discoverNewMatches() {
        List<Player> eligiblePlayers = playerRepository
                .findByAutoFetchEnabledTrueAndAuthCodeIsNotNullAndLatestShareCodeIsNotNull();

        if (eligiblePlayers.isEmpty()) {
            log.debug("Nenhum jogador ativo cadastrado para auto-fetch.");
            return;
        }

        log.debug("🔎 Ciclo de descoberta para {} jogador(es)...", eligiblePlayers.size());

        for (Player player : eligiblePlayers) {
            try {
                discoverForPlayer(player);
            } catch (Exception e) {
                log.error("Erro na descoberta para o jogador {}: {}",
                        player.getSteamId64(), e.getMessage(), e);
            }
        }
    }

    /**
     * Busca a próxima partida do jogador e a enfileira.
     *
     * @return true se uma nova partida foi descoberta
     */
    public boolean discoverForPlayer(Player player) {
        String steamId64 = player.getSteamId64();
        String currentShareCode = player.getLatestShareCode();

        player.setLastPolledAt(Instant.now());
        playerRepository.save(player);

        String nextShareCode;
        try {
            nextShareCode = valveApiService.fetchNextMatchShareCode(
                    steamId64, player.getAuthCode(), currentShareCode);
        } catch (ValveAuthException e) {
            handleInvalidCredentials(player, e);
            return false;
        } catch (ValveTransientException e) {
            log.warn("⏳ Falha temporária ao consultar a Valve para {}: {}. Retentará no próximo ciclo.",
                    player.getDisplayName(), e.getMessage());
            return false;
        }

        if (nextShareCode == null || nextShareCode.equalsIgnoreCase(currentShareCode)) {
            log.debug("Nenhuma nova partida disponível para {}", player.getDisplayName());
            return false;
        }

        log.info("🎮 Nova partida identificada para {}: {}", player.getDisplayName(), nextShareCode);

        // ORDEM CRÍTICA: o job é commitado ANTES de o ponteiro avançar.
        //
        // enqueueIfAbsent roda em REQUIRES_NEW, então ao retornar o registro já
        // está durável. Se o processo morrer entre as duas linhas, o pior caso é
        // um job órfão que o worker executa normalmente — nunca uma partida
        // perdida, que era o que acontecia quando o ponteiro avançava primeiro.
        jobService.enqueueIfAbsent(steamId64, nextShareCode, FetchSource.POLL);

        // O ponteiro avança mesmo se o processamento falhar depois, e isso é
        // proposital: GetNextMatchSharingCode é uma lista encadeada por
        // `knowncode`. Parar aqui significaria nunca descobrir a partida N+2.
        // O job durável é o que torna o avanço seguro.
        player.setLatestShareCode(nextShareCode);
        playerRepository.save(player);

        return true;
    }

    /**
     * Desabilita o auto-fetch de um jogador cujas credenciais da Valve são inválidas.
     *
     * <p>Sem isto, um auth code revogado gera um 403 a cada 5 minutos, para
     * sempre, sem que nada no sistema reaja.</p>
     */
    private void handleInvalidCredentials(Player player, ValveAuthException e) {
        log.error("🔑 Credenciais da Valve inválidas para {} ({}): {} — auto-fetch DESABILITADO.",
                player.getDisplayName(), player.getSteamId64(), e.getMessage());

        player.setAutoFetchEnabled(false);
        playerRepository.save(player);

        try {
            steamBotClientService.sendSimpleNotification(player.getSteamId64(),
                    "🔑 Não consigo mais consultar suas partidas: o Game Authentication Code "
                            + "está inválido ou foi revogado.\n\n"
                            + "Gere um novo em CS2 → Configurações → Game Authentication Code "
                            + "e cadastre novamente para reativar o monitoramento.");
        } catch (Exception notifyErr) {
            log.warn("Não foi possível avisar {}: {}", player.getSteamId64(), notifyErr.getMessage());
        }
    }
}
