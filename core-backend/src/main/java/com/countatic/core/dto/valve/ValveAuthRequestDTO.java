package com.countatic.core.dto.valve;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para cadastro/atualização de credenciais da Valve pelo jogador.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValveAuthRequestDTO {

    /**
     * SteamID64 do jogador (17 dígitos numéricos).
     */
    @NotBlank(message = "SteamID64 é obrigatório")
    @Size(min = 17, max = 17, message = "SteamID64 deve ter 17 dígitos")
    private String steamId64;

    /**
     * Game Authentication Code obtido em https://steamcommunity.com/dev/gamedata
     * Exemplo: "AAAA-BBBB-CCCC"
     */
    @NotBlank(message = "Auth Code é obrigatório")
    private String authCode;

    /**
     * Último código de compartilhamento de partida conhecido do jogador.
     * Exemplo: "CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx"
     */
    @NotBlank(message = "Share Code inicial é obrigatório")
    private String initialShareCode;

    /**
     * Nome do jogador (opcional).
     */
    private String displayName;
}
