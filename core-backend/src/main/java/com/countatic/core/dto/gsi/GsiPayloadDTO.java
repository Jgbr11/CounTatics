package com.countatic.core.dto.gsi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Payload que o CS2 envia ao endpoint de Game State Integration.
 *
 * <p><b>{@code ignoreUnknown} em toda classe aninhada não é zelo excessivo.</b>
 * O formato varia por modo de jogo — Premier, Wingman e casual trazem blocos
 * diferentes — e cada atualização do CS2 pode acrescentar campos. Sem a
 * anotação, um campo novo faria o Jackson lançar exceção, o endpoint devolveria
 * 400 e o gatilho pararia sem erro visível em lugar nenhum.</p>
 *
 * <p><b>Só mapeamos o que o serviço usa.</b> O payload completo traz também
 * estado de arma, granadas, bomba e o round corrente; ignorá-los é deliberado.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GsiPayloadDTO {

    private Provider provider;
    private MapState map;
    private PlayerState player;
    private Auth auth;

    /**
     * Identifica a instalação do CS2 que enviou o payload.
     *
     * <p>{@code provider.steamid} é <b>o dono da máquina</b>. Não confunda com
     * {@code player.steamid}, que é quem está sendo observado — ao assistir a
     * um companheiro no fim do round, os dois divergem.</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Provider {
        private String name;
        private Integer appid;
        private String steamid;
        private Long timestamp;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapState {
        private String name;
        private String mode;

        /** {@code warmup}, {@code live}, {@code intermission}, {@code gameover}. */
        private String phase;

        private Integer round;

        @JsonProperty("team_ct")
        private TeamState teamCt;

        @JsonProperty("team_t")
        private TeamState teamT;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamState {
        private Integer score;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlayerState {
        private String steamid;
        private String name;

        /** {@code CT} ou {@code T}; nulo enquanto o jogador é espectador. */
        private String team;

        @JsonProperty("match_stats")
        private MatchStats matchStats;
    }

    /** Acumulado da partida para o jogador — a razão de ser desta fase. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MatchStats {
        private Integer kills;
        private Integer assists;
        private Integer deaths;
        private Integer mvps;
        private Integer score;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Auth {
        private String token;
    }
}
