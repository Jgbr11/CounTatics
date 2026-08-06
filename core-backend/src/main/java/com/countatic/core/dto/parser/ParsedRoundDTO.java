package com.countatic.core.dto.parser;

import com.countatic.core.entity.RoundEndReason;
import com.countatic.core.entity.Team;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO representando um round individual extraído pelo Demo Parser (Go).
 *
 * <p>Cada round contém seus metadados (vencedor, motivo do fim, ticks)
 * e a lista completa de eventos que ocorreram durante o round.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedRoundDTO {

    /** Número sequencial do round (1-indexed). */
    private Integer roundNumber;

    /** Lado vencedor do round. */
    private Team winnerSide;

    /** Motivo do fim do round. */
    private RoundEndReason endReason;

    /** Tick em que o round começou (após freeze time). */
    private Integer startTick;

    /** Tick em que o round terminou. */
    private Integer endTick;

    /** Duração do round em segundos. */
    private Double durationSeconds;

    /** Se a bomba foi plantada neste round. */
    private Boolean bombPlanted;

    /** Se a bomba foi desarmada neste round. */
    private Boolean bombDefused;

    /** Placar acumulado do CT ao final do round. */
    private Integer ctScoreAfter;

    /** Placar acumulado do TR ao final do round. */
    private Integer trScoreAfter;

    /** Eventos ocorridos durante este round. */
    private List<ParsedEventDTO> events;
}
