package com.countatic.core.service;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cliente HTTP responsável por comunicar com o microsserviço Demo Parser (Go).
 *
 * <p>Envia o arquivo {@code .dem} descompactado via {@code multipart/form-data}
 * para o endpoint {@code POST /parse} e retorna o {@link ParsedDemoDTO} serializado.</p>
 */
@Slf4j
@Service
public class DemoParserClientService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DemoParserClientService(
            @Qualifier("parserRestClient") RestClient parserRestClient,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = parserRestClient;
    }

    /**
     * Envia um arquivo .dem em disco para o parser Go, sem carregá-lo em memória.
     *
     * <p>Preferir esta sobrecarga: o Spring transmite o multipart direto do
     * arquivo, então uma demo de centenas de MB não vira um {@code byte[]} no heap.</p>
     *
     * @param demoFile caminho do arquivo .dem já descompactado
     */
    public ParsedDemoDTO parseDemo(Path demoFile) {
        long sizeBytes;
        try {
            sizeBytes = Files.size(demoFile);
        } catch (Exception e) {
            throw new RuntimeException("Não foi possível ler o arquivo da demo: " + demoFile, e);
        }

        log.info("🚀 Enviando demo '{}' ({} MB) para o Demo Parser (Go)...",
                demoFile.getFileName(), String.format("%.2f", sizeBytes / (1024.0 * 1024.0)));

        return sendAndParse(new FileSystemResource(demoFile));
    }

    /**
     * Envia os bytes de um arquivo .dem para o microsserviço Go para parsing.
     *
     * <p>Mantida para uploads pequenos vindos direto de uma requisição HTTP.
     * Para demos baixadas do CDN prefira {@link #parseDemo(Path)}, que não
     * materializa o arquivo inteiro em memória.</p>
     *
     * @param fileName nome do arquivo .dem (ex: "match_123.dem")
     * @param demoBytes bytes brutos do arquivo .dem
     * @return DTO com os dados extraídos pelo parser em Go
     */
    public ParsedDemoDTO parseDemo(String fileName, byte[] demoBytes) {
        log.info("🚀 Enviando demo '{}' ({} MB) para o Demo Parser (Go)...",
                fileName, String.format("%.2f", demoBytes.length / (1024.0 * 1024.0)));

        return sendAndParse(new ByteArrayResource(demoBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
    }

    /**
     * Faz o POST /parse e desembrulha o envelope {@code {success, data}} do Go.
     *
     * <p>Usa {@link LinkedMultiValueMap} em vez de {@code MultipartBodyBuilder}:
     * este último carrega uma dependência opcional de {@code reactive-streams},
     * ausente num projeto Spring MVC puro, e explodia em
     * {@code NoClassDefFoundError: org/reactivestreams/Publisher} em tempo de
     * execução — nunca em compilação.</p>
     */
    private ParsedDemoDTO sendAndParse(Resource demoResource) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // O nome da parte precisa ser "demo": é o que o handler.go do Go espera.
        form.add("demo", demoResource);

        try {
            String responseJson = restClient.post()
                    .uri("/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            log.debug("Resposta recebida do Demo Parser Go: {}",
                    responseJson != null && responseJson.length() > 200
                            ? responseJson.substring(0, 200) + "..."
                            : responseJson);

            // A resposta do Go vem envelopada em { "success": true, "data": ParsedDemo }
            JsonNode root = objectMapper.readTree(responseJson);
            if (root.has("data")) {
                JsonNode dataNode = root.get("data");
                return objectMapper.treeToValue(dataNode, ParsedDemoDTO.class);
            } else {
                return objectMapper.readValue(responseJson, ParsedDemoDTO.class);
            }
        } catch (Exception e) {
            log.error("Erro ao enviar demo para o Demo Parser Go: {}", e.getMessage(), e);
            throw new RuntimeException("Falha na comunicação com o Demo Parser: " + e.getMessage(), e);
        }
    }
}
