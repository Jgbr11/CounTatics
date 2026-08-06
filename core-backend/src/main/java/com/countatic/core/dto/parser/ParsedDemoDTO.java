package com.countatic.core.dto.parser;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * DTO raiz representando a saída completa do Demo Parser (Go).
 *
 * <p>Este é o JSON principal que o microsserviço Go devolve após processar
 * um arquivo .dem. Contém metadados da partida e a lista completa de rounds
 * com seus eventos. O Core Backend (Java) consome este DTO para:</p>
 *
 * <ol>
 *   <li>Converter em entidades JPA e persistir no MySQL</li>
 *   <li>Aplicar as strategies de cálculo de métricas</li>
 *   <li>Gerar o relatório final para o Steam Bot</li>
 * </ol>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedDemoDTO {

    /** Nome do mapa (ex: "de_mirage", "de_dust2"). */
    private String mapName;

    /** Nome do servidor (se disponível na demo). */
    private String serverName;

    /** Duração total da partida em segundos. */
    private Integer durationSeconds;

    /** Placar final dos CTs. */
    private Integer scoreCT;

    /** Placar final dos TRs. */
    private Integer scoreTR;

    /** Número total de rounds jogados. */
    private Integer totalRounds;

    /** Tick rate do servidor (64 ou 128). */
    private Integer tickRate;

    /** Data/hora em que a partida foi jogada (extraída da demo). */
    private Instant playedAt;

    /** Lista de rounds com seus eventos. */
    private List<ParsedRoundDTO> rounds;
}
