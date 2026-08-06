package com.countatic.core.service;

import com.countatic.core.dto.valve.ValveNextMatchResponseDTO;
import com.countatic.core.util.CS2ShareCodeDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Serviço responsável pela integração com a API oficial da Valve (CS2 Match Sharing API).
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Consultar a Valve para verificar se o jogador jogou uma nova partida</li>
 *   <li>Obter o próximo Share Code da partida</li>
 *   <li>Baixar o arquivo de replay {@code .dem.bz2} do CDN da Valve</li>
 *   <li>Descompactar o BZip2 em memória produzindo os bytes brutos do arquivo {@code .dem}</li>
 * </ul>
 */
@Slf4j
@Service
public class ValveApiService {

    private final RestClient restClient;
    private final String steamWebApiKey;

    public ValveApiService(
            @Value("${steam.web-api-key:DEVELOPMENT_KEY}") String steamWebApiKey) {
        this.steamWebApiKey = steamWebApiKey;
        this.restClient = RestClient.builder().build();
    }

    /**
     * Consulta a API da Valve para verificar se existe uma nova partida para o jogador.
     *
     * @param steamId64    SteamID64 do jogador (17 dígitos)
     * @param authCode     Game Authentication Code
     * @param knownCode    Último Share Code conhecido do jogador
     * @return o próximo Share Code, se houver nova partida; null se não houver novas partidas
     */
    public String fetchNextMatchShareCode(String steamId64, String authCode, String knownCode) {
        log.debug("Consultando próxima partida na Valve para jogador {} (knownCode: {})",
                steamId64, knownCode);

        String url = String.format(
                "https://api.steampowered.com/ICSGOPlayers_730/GetNextMatchSharingCode/v1?key=%s&steamid=%s&steamidkey=%s&knowncode=%s",
                steamWebApiKey, steamId64, authCode, knownCode
        );

        try {
            ValveNextMatchResponseDTO response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(ValveNextMatchResponseDTO.class);

            if (response != null && response.getResult() != null) {
                String nextCode = response.getResult().getNextCode();
                if (nextCode != null && !nextCode.isBlank() && !"n/a".equalsIgnoreCase(nextCode)) {
                    log.info("🎮 Nova partida encontrada para jogador {}: {}", steamId64, nextCode);
                    return nextCode;
                }
            }

            log.debug("Nenhuma nova partida encontrada para jogador {}", steamId64);
            return null;
        } catch (Exception e) {
            log.error("Erro ao consultar próxima partida na API da Valve para {}: {}",
                    steamId64, e.getMessage());
            return null;
        }
    }

    /**
     * Baixa uma demo compactada (.dem.bz2) a partir de uma URL de CDN da Valve
     * e descompacta em memória retornando os bytes brutos do arquivo .dem.
     *
     * @param demoUrl URL do arquivo .dem.bz2 no CDN da Valve
     * @return bytes do arquivo .dem descompactado
     */
    public byte[] downloadAndDecompressDemo(String demoUrl) {
        log.info("📥 Baixando demo do CDN da Valve: {}", demoUrl);

        try {
            byte[] compressedBytes = restClient.get()
                    .uri(demoUrl)
                    .retrieve()
                    .body(byte[].class);

            if (compressedBytes == null || compressedBytes.length == 0) {
                throw new IllegalStateException("Download da demo retornou 0 bytes de " + demoUrl);
            }

            log.info("Download concluído ({} MB). Descompactando BZip2...",
                    String.format("%.2f", compressedBytes.length / (1024.0 * 1024.0)));

            return decompressBZip2(compressedBytes);
        } catch (Exception e) {
            log.error("Erro ao baixar/descompactar demo da URL {}: {}", demoUrl, e.getMessage(), e);
            throw new RuntimeException("Falha no download da demo: " + e.getMessage(), e);
        }
    }

    /**
     * Descompacta um array de bytes no formato BZip2.
     *
     * @param compressedData bytes do arquivo .bz2
     * @return bytes descompactados do arquivo .dem
     */
    public byte[] decompressBZip2(byte[] compressedData) {
        try (InputStream bin = new ByteArrayInputStream(compressedData);
             BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(bin);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int n;
            while ((n = bzIn.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }

            byte[] decompressed = out.toByteArray();
            log.info("Descompactação BZip2 concluída com sucesso! Tamanho final: {} MB",
                    String.format("%.2f", decompressed.length / (1024.0 * 1024.0)));

            return decompressed;
        } catch (Exception e) {
            log.error("Erro durante a descompactação BZip2: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Arquivo BZip2 corrompido ou inválido: " + e.getMessage(), e);
        }
    }
}
