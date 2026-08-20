package com.countatic.core.dto.stats;

import com.countatic.core.award.MatchAwardType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Título do jogador na partida, pronto para exibição.
 *
 * <p>Carrega nome, descrição e categoria já resolvidos em vez de só o
 * identificador do enum. A interface precisa dos três para desenhar o selo, e
 * duplicar os textos no JavaScript significaria dois lugares para reescrever
 * cada vez que um título mudasse de nome.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AwardDTO {

    /** Nome da constante, ex: {@code "REI_DO_CLUTCH"} — estável para o CSS. */
    private String id;

    private String nome;
    private String descricao;

    /** {@code EPICO}, {@code NEUTRO} ou {@code COMICO} — define a cor do selo. */
    private String categoria;

    public static AwardDTO de(MatchAwardType tipo) {
        return AwardDTO.builder()
                .id(tipo.name())
                .nome(tipo.getNome())
                .descricao(tipo.getDescricao())
                .categoria(tipo.getCategoria().name())
                .build();
    }
}
