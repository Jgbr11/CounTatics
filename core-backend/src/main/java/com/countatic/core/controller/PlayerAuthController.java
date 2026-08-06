package com.countatic.core.controller;

import com.countatic.core.dto.valve.ValveAuthRequestDTO;
import com.countatic.core.entity.Player;
import com.countatic.core.repository.PlayerRepository;
import com.countatic.core.service.ValveDemoFetcherScheduler;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Controller REST para cadastro e gerenciamento das credenciais da Valve (CS2 Match Sharing API).
 */
@Slf4j
@RestController
@RequestMapping("/api/players")
public class PlayerAuthController {

    private final PlayerRepository playerRepository;
    private final ValveDemoFetcherScheduler fetcherScheduler;

    public PlayerAuthController(
            PlayerRepository playerRepository,
            ValveDemoFetcherScheduler fetcherScheduler) {
        this.playerRepository = playerRepository;
        this.fetcherScheduler = fetcherScheduler;
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
     * Endpoint para forçar a busca imediata de novas partidas de um jogador.
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

        boolean foundNewMatch = fetcherScheduler.processNextMatchForPlayer(player);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "steamId", steamId,
                "newMatchFound", foundNewMatch,
                "latestShareCode", player.getLatestShareCode()
        ));
    }
}
