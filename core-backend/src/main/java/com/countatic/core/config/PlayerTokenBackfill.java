package com.countatic.core.config;

import com.countatic.core.entity.Player;
import com.countatic.core.repository.PlayerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Gera o {@code publicToken} dos jogadores cadastrados antes de a coluna existir.
 *
 * <p><b>Por que não basta o {@code @PrePersist}.</b> Ele só dispara em insert.
 * Sem este preenchimento, quem já estava no banco ficaria com token nulo e o
 * painel responderia 404 para o próprio dono — justamente o usuário que o
 * sistema tem hoje.</p>
 *
 * <p>Roda uma vez no start e não faz nada nas execuções seguintes, porque a
 * consulta deixa de encontrar linhas. É intencionalmente burro: não há
 * migração no projeto ({@code ddl-auto: update}), então este é o lugar onde um
 * preenchimento de coluna nova cabe sem inventar infraestrutura.</p>
 */
@Slf4j
@Component
public class PlayerTokenBackfill implements ApplicationRunner {

    private final PlayerRepository playerRepository;

    public PlayerTokenBackfill(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Player> semToken = playerRepository.findByPublicTokenIsNull();
        if (semToken.isEmpty()) {
            return;
        }

        semToken.forEach(p -> p.setPublicToken(UUID.randomUUID().toString()));
        playerRepository.saveAll(semToken);

        log.info("🔑 Token de painel gerado para {} jogador(es) já cadastrado(s). "
                + "Consulte a URL em GET /api/players/{}/painel", semToken.size(), "{steamId}");
    }
}
