package com.countatic.core.repository;

import com.countatic.core.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositório para acesso a dados de jogadores.
 */
@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {

    /**
     * Busca um jogador pelo SteamID64.
     *
     * @param steamId64 o identificador único de 17 dígitos da Steam
     * @return o jogador, se encontrado
     */
    Optional<Player> findBySteamId64(String steamId64);

    /**
     * Verifica se já existe um jogador com o SteamID64 informado.
     *
     * @param steamId64 o identificador único de 17 dígitos da Steam
     * @return true se o jogador já está cadastrado
     */
    boolean existsBySteamId64(String steamId64);
}
