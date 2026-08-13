package com.countatic.core.controller;

import com.countatic.core.dto.valve.ValveAuthRequestDTO;
import com.countatic.core.entity.Player;
import com.countatic.core.exception.ValveAuthException;
import com.countatic.core.exception.ValveTransientException;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.service.MatchDiscoveryScheduler;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Controller REST para cadastro e gerenciamento das credenciais da Valve (CS2 Match Sharing API).
 */
@Slf4j
@RestController
@RequestMapping("/api/players")
public class PlayerAuthController {

    /**
     * Forma geral do Game Authentication Code do CS2: três grupos alfanuméricos
     * separados por hífen (ex: {@code ABCD-EFGHI-JKLM}).
     *
     * <p>Deliberadamente <b>não</b> fixa o tamanho exato de cada grupo. Só temos
     * amostras reais suficientes para confirmar o formato geral, e uma regra
     * estreita demais bloquearia um usuário legítimo cujo código fugisse do
     * padrão observado. A defesa real contra credencial inválida é o
     * {@code ValveAuthException} → desabilitar o auto-fetch, que reage ao 403
     * da própria Valve já no primeiro ciclo. Aqui só barramos entrada
     * claramente malformada (sem hífen, com espaço, tamanho absurdo).</p>
     */
    private static final Pattern AUTH_CODE_PATTERN =
            Pattern.compile("^[A-Za-z0-9]{4,6}(-[A-Za-z0-9]{4,6}){2}$");

    private final PlayerRepository playerRepository;
    private final MatchDiscoveryScheduler discoveryScheduler;
    private final String botSteamId;

    public PlayerAuthController(
            PlayerRepository playerRepository,
            MatchDiscoveryScheduler discoveryScheduler,
            @Value("${countatic.bot-steam-id:}") String botSteamId) {
        this.playerRepository = playerRepository;
        this.discoveryScheduler = discoveryScheduler;
        this.botSteamId = botSteamId;
    }

    /**
     * Endpoint para o jogador cadastrar seu Game Authentication Code e Share Code inicial.
     *
     * <p>Exemplo de Payload:</p>
     * <pre>
     * {
     *   "steamId64": "76561198012345678",
     *   "authCode": "AAAA-BBBB-CCCC",
     *   "initialShareCode": "CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx",
     *   "displayName": "Fallen"
     * }
     * </pre>
     */
    @PostMapping("/auth")
    public ResponseEntity<?> registerValveAuth(@Valid @RequestBody ValveAuthRequestDTO dto) {
        log.info("Recebido cadastro de Valve Auth para SteamID: {}", dto.getSteamId64());

        // A conta do bot não joga partidas: cadastrá-la só produz 403 da Valve
        // a cada ciclo do scheduler, para sempre.
        if (botSteamId != null && !botSteamId.isBlank() && botSteamId.equals(dto.getSteamId64())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Este SteamID é o da conta do bot. Cadastre o SteamID do JOGADOR."
            ));
        }

        if (!AUTH_CODE_PATTERN.matcher(dto.getAuthCode()).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Game Authentication Code inválido. "
                            + "Esperado: três grupos alfanuméricos separados por hífen "
                            + "(ex: ABCD-EFGHI-JKLM). Gere o seu em "
                            + "CS2 → Configurações → Game Authentication Code."
            ));
        }

        Optional<Player> existing = playerRepository.findBySteamId64(dto.getSteamId64());
        Player player;

        if (existing.isPresent()) {
            player = existing.get();
        } else {
            player = Player.builder()
                    .steamId64(dto.getSteamId64())
                    .displayName(dto.getDisplayName() != null ? dto.getDisplayName() : "Player")
                    .build();
        }

        player.setAuthCode(dto.getAuthCode());
        player.setLatestShareCode(dto.getInitialShareCode());
        player.setAutoFetchEnabled(true);

        if (dto.getDisplayName() != null && !dto.getDisplayName().isBlank()) {
            player.setDisplayName(dto.getDisplayName());
        }

        Player saved = playerRepository.save(player);
        log.info("✅ Credenciais da Valve salvas para {} com auto-fetch habilitado!", saved.getDisplayName());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Credenciais da Valve registradas com sucesso. Auto-fetch ativado!",
                "steamId64", saved.getSteamId64(),
                "autoFetchEnabled", saved.getAutoFetchEnabled(),
                "latestShareCode", saved.getLatestShareCode()
        ));
    }

    /**
     * Remove um jogador do sistema.
     *
     * <p>Existe principalmente para limpar cadastros incorretos (credenciais
     * falsas, SteamID errado) que, uma vez no banco, geram erro em todo ciclo
     * do scheduler.</p>
     */
    @DeleteMapping("/{steamId}")
    public ResponseEntity<?> deletePlayer(@PathVariable("steamId") String steamId) {
        Optional<Player> existing = playerRepository.findBySteamId64(steamId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        playerRepository.delete(existing.get());
        log.info("🗑️ Jogador {} removido do sistema.", steamId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "steamId", steamId,
                "message", "Jogador removido com sucesso."
        ));
    }

    /**
     * Liga ou desliga o monitoramento automático de um jogador sem apagá-lo.
     */
    @PatchMapping("/{steamId}/auto-fetch")
    public ResponseEntity<?> setAutoFetch(@PathVariable("steamId") String steamId,
                                          @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Campo 'enabled' (boolean) é obrigatório."
            ));
        }

        Optional<Player> existing = playerRepository.findBySteamId64(steamId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Player player = existing.get();
        player.setAutoFetchEnabled(enabled);
        playerRepository.save(player);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "steamId", steamId,
                "autoFetchEnabled", enabled
        ));
    }

    /**
     * Dispara imediatamente a DESCOBERTA de novas partidas do jogador.
     *
     * <p>Responde <b>202 Accepted</b>: a descoberta é rápida (uma chamada à
     * Valve), mas o processamento — Game Coordinator, download de centenas de
     * MB, parsing — fica a cargo do {@code MatchJobWorker}.</p>
     *
     * <p>Antes esse endpoint executava o pipeline inteiro na thread HTTP, e a
     * conexão do cliente caía por timeout no meio do download.
     * Acompanhe o progresso em {@code GET /api/players/{steamId}/jobs}.</p>
     */
    @PostMapping("/{steamId}/fetch-now")
    public ResponseEntity<?> forceFetchNextMatch(@PathVariable("steamId") String steamId) {
        log.info("Solicitação de fetch manual para o jogador {}", steamId);

        Optional<Player> existing = playerRepository.findBySteamId64(steamId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Player player = existing.get();
        if (player.getAuthCode() == null || player.getLatestShareCode() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Jogador não possui Auth Code ou Share Code cadastrados. Faça POST /api/players/auth primeiro."
            ));
        }

        boolean foundNewMatch = discoveryScheduler.discoverForPlayer(player);

        return ResponseEntity.accepted().body(Map.of(
                "success", true,
                "steamId", steamId,
                "newMatchFound", foundNewMatch,
                "latestShareCode", player.getLatestShareCode(),
                "message", foundNewMatch
                        ? "Partida enfileirada. Acompanhe em GET /api/players/" + steamId + "/jobs"
                        : "Nenhuma partida nova encontrada."
        ));
    }

    /**
     * Endpoint para avançar rapidamente até a partida mais recente do jogador,
     * pulando todas as partidas antigas sem processá-las ou enviar notificações.
     *
     * <p>Útil quando o jogador cadastra um Share Code antigo e o sistema precisa
     * "alcançar" a partida mais recente sem spammar notificações.</p>
     */
    @PostMapping("/{steamId}/skip-to-latest")
    public ResponseEntity<?> skipToLatest(@PathVariable("steamId") String steamId) {
        log.info("Solicitação de skip-to-latest para o jogador {}", steamId);

        Optional<Player> existing = playerRepository.findBySteamId64(steamId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Player player = existing.get();
        if (player.getAuthCode() == null || player.getLatestShareCode() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Jogador não possui Auth Code ou Share Code cadastrados."
            ));
        }

        String currentCode = player.getLatestShareCode();
        int skippedCount = 0;
        final int maxIterations = 500; // Segurança contra loop infinito

        log.info("Avançando share codes a partir de: {}", currentCode);

        for (int i = 0; i < maxIterations; i++) {
            String nextCode;
            try {
                nextCode = discoveryScheduler.getValveApiService()
                        .fetchNextMatchShareCode(player.getSteamId64(), player.getAuthCode(), currentCode);
            } catch (ValveAuthException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Credenciais da Valve inválidas: " + e.getMessage(),
                        "skippedMatches", skippedCount
                ));
            } catch (ValveTransientException e) {
                // Interrompe e persiste o progresso já feito em vez de perdê-lo.
                log.warn("Skip-to-latest interrompido por falha temporária após {} pulos: {}",
                        skippedCount, e.getMessage());
                break;
            }

            if (nextCode == null || nextCode.equalsIgnoreCase(currentCode)) {
                // Não há mais partidas — estamos em dia!
                break;
            }

            currentCode = nextCode;
            skippedCount++;
            log.debug("Skip #{}: {}", skippedCount, nextCode);
        }

        // Salvar o share code mais recente no banco
        player.setLatestShareCode(currentCode);
        playerRepository.save(player);

        log.info("✅ Skip-to-latest concluído para {}. {} partidas puladas. Share code atual: {}",
                player.getDisplayName(), skippedCount, currentCode);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "steamId", steamId,
                "skippedMatches", skippedCount,
                "latestShareCode", currentCode,
                "message", skippedCount > 0
                        ? String.format("%d partidas antigas puladas. Sistema em dia!", skippedCount)
                        : "Sistema já estava em dia. Nenhuma partida para pular."
        ));
    }
}
