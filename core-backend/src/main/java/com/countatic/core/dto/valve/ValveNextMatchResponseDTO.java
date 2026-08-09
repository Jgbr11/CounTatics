package com.countatic.core.dto.valve;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
// Sem isto, qualquer campo extra que a Valve adicione à resposta quebra a
// desserialização inteira — e a falha aparecia como "nenhuma partida nova".
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValveNextMatchResponseDTO {

    private ResultData result;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultData {
        @JsonProperty("nextcode")
        private String nextCode;
    }
}
