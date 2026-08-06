package com.countatic.core.service;

import com.countatic.core.dto.parser.ParsedDemoDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
            @Value("${services.parser-url:http://localhost:8081}") String parserUrl,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(parserUrl)
                .build();
    }

    /**
     * Envia os bytes de um arquivo .dem para o microsserviço Go para parsing.
     *
     * @param fileName nome do arquivo .dem (ex: "match_123.dem")
     * @param demoBytes bytes brutos do arquivo .dem
     * @return DTO com os dados extraídos pelo parser em Go
     */
    public ParsedDemoDTO parseDemo(String fileName, byte[] demoBytes) {
        log.info("🚀 Enviando demo '{}' ({} MB) para o Demo Parser (Go)...",
                fileName, String.format("%.2f", demoBytes.length / (1024.0 * 1024.0)));

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("demo", new ByteArrayResource(demoBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }, MediaType.APPLICATION_OCTET_STREAM);

        try {
            String responseJson = restClient.post()
                    .uri("/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
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
