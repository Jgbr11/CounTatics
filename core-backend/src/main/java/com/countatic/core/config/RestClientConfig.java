package com.countatic.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuração central dos clientes HTTP de saída.
 *
 * <p><b>Por que existe:</b> os três serviços construíam {@code RestClient.builder().build()}
 * sem nenhum timeout. Um serviço lento (ou pendurado) bloqueava a thread do scheduler
 * indefinidamente, e como o scheduler era single-threaded, um jogador travado
 * impedia o processamento de todos os outros.</p>
 *
 * <p>Cada destino tem uma característica de latência diferente, então os timeouts
 * são calibrados individualmente em vez de um valor único global.</p>
 */
@Configuration
public class RestClientConfig {

    /**
     * API pública da Valve — respostas JSON pequenas e rápidas.
     */
    @Bean
    public RestClient valveRestClient() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofSeconds(10))))
                .build();
    }

    /**
     * Steam Bot (Node.js).
     *
     * <p>O read timeout precisa cobrir as duas operações mais lentas do bot:</p>
     * <ul>
     *   <li>{@code /match-info}: até 15 s aguardando o Game Coordinator.
     *       Cortar antes disso transformaria um GC lento num erro de rede
     *       genérico, em vez do 504 que o bot devolveria.</li>
     *   <li>{@code /notify}: até ~65 s quando a Steam aplica rate limit e o
     *       bot faz backoff de 5 s + 15 s + 45 s. Isso acontece de forma
     *       rotineira ao enviar o relatório básico e o profundo em sequência.</li>
     * </ul>
     */
    @Bean
    public RestClient botRestClient(
            @Value("${services.bot-url:http://localhost:3000}") String botUrl) {
        return RestClient.builder()
                .baseUrl(botUrl)
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(3))
                                .withReadTimeout(Duration.ofSeconds(90))))
                .build();
    }

    /**
     * Demo Parser (Go).
     *
     * <p>Parsear uma demo de CS2 tick a tick leva minutos. O read timeout
     * acompanha o {@code WriteTimeout} de 10 min configurado no
     * {@code cmd/server/main.go} do serviço Go.</p>
     */
    @Bean
    public RestClient parserRestClient(
            @Value("${services.parser-url:http://localhost:8081}") String parserUrl) {
        return RestClient.builder()
                .baseUrl(parserUrl)
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofMinutes(10))))
                .build();
    }

    /**
     * Download de demos do CDN da Valve.
     *
     * <p>O read timeout é por leitura de chunk, não para a transferência
     * inteira — um arquivo de centenas de MB continua podendo levar minutos,
     * desde que os dados sigam chegando.</p>
     */
    @Bean
    public RestClient demoDownloadRestClient() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(10))
                                .withReadTimeout(Duration.ofSeconds(60))))
                .build();
    }
}
