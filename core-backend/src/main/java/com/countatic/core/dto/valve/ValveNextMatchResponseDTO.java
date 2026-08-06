package com.countatic.core.dto.valve;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO que mapeia a resposta da API oficial da Valve:
 * https://api.steampowered.com/ICSGOPlayers_730/GetNextMatchSharingCode/v1
 *
 * <p>Formato de resposta da Valve:</p>
 * <pre>
 * {
 *   "result": {
 *     "nextcode": "CSGO-yyyyy-yyyyy-yyyyy-yyyyy-yyyyy"
 *   }
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValveNextMatchResponseDTO {

    private ResultData result;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResultData {
        @JsonProperty("nextcode")
        private String nextCode;
    }
}
