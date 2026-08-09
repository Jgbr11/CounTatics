package com.countatic.core.service;

import com.countatic.core.dto.valve.ValveNextMatchResponseDTO;
import com.countatic.core.exception.DemoExpiredException;
import com.countatic.core.exception.ValveAuthException;
import com.countatic.core.exception.ValveTransientException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Serviço responsável pela integração com a API oficial da Valve (CS2 Match Sharing API).
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Consultar a Valve para verificar se o jogador jogou uma nova partida</li>
 *   <li>Obter o próximo Share Code da partida</li>
 *   <li>Baixar e descompactar o replay {@code .dem.bz2} do CDN da Valve</li>
 * </ul>
 */
@Slf4j
@Service
public class ValveApiService {

    private static final String NEXT_MATCH_URL =
            "https://api.steampowered.com/ICSGOPlayers_730/GetNextMatchSharingCode/v1"
                    + "?key=%s&steamid=%s&steamidkey=%s&knowncode=%s";

    /** Margem de segurança de espaço em disco antes de iniciar um download. */
    private static final long MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;

    private final RestClient valveRestClient;
    private final RestClient demoDownloadRestClient;
    private final String steamWebApiKey;

    public ValveApiService(
            @Qualifier("valveRestClient") RestClient valveRestClient,
            @Qualifier("demoDownloadRestClient") RestClient demoDownloadRestClient,
            @Value("${steam.web-api-key:}") String steamWebApiKey) {
        this.valveRestClient = valveRestClient;
        this.demoDownloadRestClient = demoDownloadRestClient;
        this.steamWebApiKey = steamWebApiKey;

        if (steamWebApiKey == null || steamWebApiKey.isBlank()) {
            log.error("⚠️ steam.web-api-key não configurada! Defina STEAM_WEB_API_KEY no .env. "
                    + "Toda consulta à API da Valve vai falhar com 403.");
        }
    }

    /**
     * Consulta a API da Valve para verificar se existe uma nova partida para o jogador.
     *
     * <p>Antes, todo erro era engolido e virava {@code null}, tornando um 403
     * (API key ou auth code inválido) indistinguível de "não há partida nova" —
     * o resultado era um erro repetido a cada ciclo do scheduler, para sempre,
     * sem que nada no sistema reagisse.</p>
     *
     * @return o próximo Share Code, ou {@code null} se não houver nova partida
     * @throws ValveAuthException      credencial inválida — falha permanente para este jogador
     * @throws ValveTransientException falha temporária (5xx, rede) — vale retentar
     */
    public String fetchNextMatchShareCode(String steamId64, String authCode, String knownCode) {
        log.debug("Consultando próxima partida na Valve para jogador {} (knownCode: {})",
                steamId64, knownCode);

        String url = String.format(NEXT_MATCH_URL,
                enc(steamWebApiKey), enc(steamId64), enc(authCode), enc(knownCode));

        try {
            ValveNextMatchResponseDTO response = valveRestClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(status -> status == HttpStatus.FORBIDDEN
                                     || status == HttpStatus.UNAUTHORIZED,
                            (req, res) -> {
                                throw new ValveAuthException(steamId64,
                                        "A Valve rejeitou as credenciais (HTTP "
                                                + res.getStatusCode().value() + "). "
                                                + "Steam Web API Key inválida ou "
                                                + "Game Authentication Code errado/revogado.");
                            })
                    // 202 = sem partida nova ainda; 412 = knowncode não pertence ao jogador.
                    // Ambos são respostas normais, não erros.
                    .onStatus(status -> status == HttpStatus.ACCEPTED
                                     || status == HttpStatus.PRECONDITION_FAILED
                                     || status == HttpStatus.NOT_FOUND,
                            (req, res) -> { /* tratado como "sem partida nova" abaixo */ })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (req, res) -> {
                                throw new ValveTransientException(
                                        "API da Valve indisponível (HTTP "
                                                + res.getStatusCode().value() + ")");
                            })
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
        } catch (ValveAuthException | ValveTransientException e) {
            throw e;
        } catch (Exception e) {
            // Rede/timeout/parse: transitório por natureza.
            throw new ValveTransientException(
                    "Falha ao consultar a API da Valve para " + steamId64 + ": " + e.getMessage(), e);
        }
    }

    /** Resultado de um download de demo já descompactado em disco. */
    public record DownloadedDemo(Path demoFile, String sha256, long sizeBytes) {
    }

    /**
     * Baixa e descompacta um replay do CDN da Valve direto para disco.
     *
     * <p>A versão anterior carregava o arquivo inteiro em heap duas vezes —
     * comprimido e descomprimido — o que para uma demo típica de CS2 significa
     * 400-600 MB de pico. Aqui o stream é encadeado
     * ({@code HTTP -> BZip2 -> DigestOutputStream -> arquivo}), então o SHA-256
     * sai na mesma passada da escrita e nenhum {@code byte[]} completo existe.</p>
     *
     * @throws DemoExpiredException    404 do CDN — demo fora da janela de retenção (~14 dias)
     * @throws ValveTransientException falha temporária de rede/servidor
     */
    public DownloadedDemo downloadAndDecompressToFile(String demoUrl, Path targetDir) {
        log.info("📥 Baixando demo do CDN da Valve: {}", demoUrl);

        try {
            Files.createDirectories(targetDir);
            assertEnoughDiskSpace(targetDir);
        } catch (DemoExpiredException | ValveTransientException e) {
            throw e;
        } catch (Exception e) {
            throw new ValveTransientException(
                    "Não foi possível preparar o diretório temporário " + targetDir, e);
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile(targetDir, "countatic-", ".dem");
        } catch (Exception e) {
            throw new ValveTransientException("Falha ao criar arquivo temporário para a demo", e);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            Long written = demoDownloadRestClient.get()
                    .uri(demoUrl)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());

                        if (status == HttpStatus.NOT_FOUND || status == HttpStatus.GONE) {
                            throw new DemoExpiredException(
                                    "Demo não está mais disponível no CDN da Valve (HTTP "
                                            + response.getStatusCode().value() + "). "
                                            + "Replays de CS2 expiram após cerca de 2 semanas.");
                        }
                        if (response.getStatusCode().isError()) {
                            throw new ValveTransientException(
                                    "CDN da Valve devolveu HTTP "
                                            + response.getStatusCode().value()
                                            + " para " + demoUrl);
                        }

                        try (InputStream in = response.getBody();
                             BZip2CompressorInputStream bzIn =
                                     new BZip2CompressorInputStream(in, true);
                             OutputStream fileOut = Files.newOutputStream(tempFile);
                             DigestOutputStream out = new DigestOutputStream(fileOut, digest)) {

                            return bzIn.transferTo(out);
                        }
                    });

            long size = written == null ? 0L : written;
            if (size == 0) {
                throw new ValveTransientException("Download da demo retornou 0 bytes de " + demoUrl);
            }

            String sha256 = HexFormat.of().formatHex(digest.digest());
            log.info("✅ Demo baixada e descompactada: {} MB — sha256={}",
                    String.format("%.2f", size / (1024.0 * 1024.0)), sha256);

            return new DownloadedDemo(tempFile, sha256, size);
        } catch (NoSuchAlgorithmException e) {
            deleteQuietly(tempFile);
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        } catch (DemoExpiredException | ValveTransientException e) {
            deleteQuietly(tempFile);
            throw e;
        } catch (Exception e) {
            deleteQuietly(tempFile);
            throw new ValveTransientException(
                    "Falha ao baixar/descompactar a demo de " + demoUrl + ": " + e.getMessage(), e);
        }
    }

    /** Remove um arquivo temporário sem propagar falhas de limpeza. */
    public void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("Não foi possível remover o arquivo temporário {}: {}", file, e.getMessage());
        }
    }

    private void assertEnoughDiskSpace(Path dir) throws Exception {
        FileStore store = Files.getFileStore(dir);
        long usable = store.getUsableSpace();
        if (usable < MIN_FREE_BYTES) {
            throw new ValveTransientException(String.format(
                    "Espaço em disco insuficiente em %s: %.2f GB livres, mínimo %.2f GB.",
                    dir, usable / (1024.0 * 1024 * 1024), MIN_FREE_BYTES / (1024.0 * 1024 * 1024)));
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
