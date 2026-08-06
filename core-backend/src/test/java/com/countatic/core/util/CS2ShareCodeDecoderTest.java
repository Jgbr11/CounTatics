package com.countatic.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CS2ShareCodeDecoderTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "CSGO-7K4uX-B495P-LhnF7-66X8R-3S83G",
            "CSGO-AAAAA-BBBBB-CCCCC-DDDDD-EEEEE",
            "CSGO-23456-789AB-CDEFH-JKLMN-PQRST"
    })
    @DisplayName("Deve validar formatos corretos de Share Code do CS2")
    void shouldValidateCorrectShareCodeFormat(String code) {
        assertThat(CS2ShareCodeDecoder.isValidShareCode(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INVALID-CODE",
            "CSGO-12345-67890-12345-67890-12345", // Contém '1' e '0' que não estão no alfabeto Base57 da Valve
            "CSGO-SHORT-CODE",
            "",
            "   "
    })
    @DisplayName("Deve rejeitar formatos inválidos de Share Code")
    void shouldRejectInvalidShareCodeFormat(String code) {
        assertThat(CS2ShareCodeDecoder.isValidShareCode(code)).isFalse();
    }

    @Test
    @DisplayName("Deve decodificar Share Code válido em componentes numéricos")
    void shouldDecodeValidShareCode() {
        String validCode = "CSGO-7K4uX-B495P-LhnF7-66X8R-3S83G";

        CS2ShareCodeDecoder.DecodedShareCode decoded = CS2ShareCodeDecoder.decode(validCode);

        assertThat(decoded).isNotNull();
        assertThat(decoded.getOriginalCode()).isEqualTo(validCode);
        assertThat(decoded.getMatchId()).isNotNull();
        assertThat(decoded.getOutcomeId()).isNotNull();
        assertThat(decoded.getToken()).isNotNull();
    }

    @Test
    @DisplayName("Deve lançar exceção ao tentar decodificar Share Code inválido")
    void shouldThrowExceptionForInvalidCode() {
        assertThatThrownBy(() -> CS2ShareCodeDecoder.decode("INVALID_SHARE_CODE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Share code inválido");
    }
}
