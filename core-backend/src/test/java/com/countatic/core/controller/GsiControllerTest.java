package com.countatic.core.controller;

import com.countatic.core.dto.gsi.GsiPayloadDTO;
import com.countatic.core.service.GsiEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GsiControllerTest {

    private final GsiEventService service = mock(GsiEventService.class);

    @Test
    @DisplayName("Aceita o payload quando o token confere")
    void aceitaTokenCorreto() {
        GsiController controller = new GsiController(service, "segredo-correto");

        var resposta = controller.receber(payloadComToken("segredo-correto"));

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        verify(service).processar(any());
    }

    @Test
    @DisplayName("Recusa token errado sem tocar no pipeline")
    void recusaTokenErrado() {
        GsiController controller = new GsiController(service, "segredo-correto");

        var resposta = controller.receber(payloadComToken("chute"));

        assertThat(resposta.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).processar(any());
    }

    @Test
    @DisplayName("Recusa payload sem bloco de auth")
    void recusaSemAuth() {
        GsiController controller = new GsiController(service, "segredo-correto");

        var resposta = controller.receber(new GsiPayloadDTO());

        assertThat(resposta.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).processar(any());
    }

    @Test
    @DisplayName("Token não configurado recusa tudo — endpoint aberto na porta 8080 seria "
            + "convite para qualquer um enfileirar jobs")
    void tokenVazioRecusaTudo() {
        GsiController controller = new GsiController(service, "");

        assertThat(controller.receber(payloadComToken("")).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.receber(payloadComToken("qualquer")).getStatusCode().value()).isEqualTo(403);
        verify(service, never()).processar(any());
    }

    private GsiPayloadDTO payloadComToken(String token) {
        GsiPayloadDTO p = new GsiPayloadDTO();
        GsiPayloadDTO.Auth auth = new GsiPayloadDTO.Auth();
        auth.setToken(token);
        p.setAuth(auth);
        return p;
    }
}
