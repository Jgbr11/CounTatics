package com.countatic.core.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Utilitário para decodificação de Share Codes do Counter-Strike 2.
 *
 * <p>Share Codes do CS2 têm o formato: {@code CSGO-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX}</p>
 * O código usa um alfabeto Base57 customizado que codifica 144 bits:
 * <ul>
 *   <li>64 bits: Match ID</li>
 *   <li>64 bits: Outcome ID / Reservation ID</li>
 *   <li>16 bits: TV Token</li>
 * </ul>
 */
@Slf4j
public class CS2ShareCodeDecoder {

    /**
     * Alfabeto Base57 usado pela Valve para Share Codes do CS2/CSGO.
     * (Exclui I, O, 0, 1 para evitar ambiguidade visual).
     */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length());

    private static final Pattern SHARE_CODE_PATTERN =
            Pattern.compile("^CSGO(-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}){5}$");

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DecodedShareCode {
        private BigInteger matchId;
        private BigInteger outcomeId;
        private Integer token;
        private String originalCode;
    }

    /**
     * Valida se uma string é um Share Code válido no formato CSGO-XXXXX-XXXXX-...
     */
    public static boolean isValidShareCode(String code) {
        return code != null && SHARE_CODE_PATTERN.matcher(code.trim().toUpperCase()).matches();
    }

    /**
     * Decodifica um Share Code do CS2 em seus componentes (Match ID, Outcome ID e Token).
     *
     * @param shareCode o código no formato CSGO-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX
     * @return os componentes numéricos decodificados
     */
    public static DecodedShareCode decode(String shareCode) {
        if (!isValidShareCode(shareCode)) {
            throw new IllegalArgumentException("Share code inválido: " + shareCode);
        }

        String cleaned = shareCode.replace("CSGO-", "").replace("-", "").toUpperCase();
        String reversed = new StringBuilder(cleaned).reverse().toString();

        BigInteger bigInt = BigInteger.ZERO;
        for (int i = 0; i < reversed.length(); i++) {
            char c = reversed.charAt(i);
            int index = ALPHABET.indexOf(c);
            if (index == -1) {
                throw new IllegalArgumentException("Caractere inválido no share code: " + c);
            }
            bigInt = bigInt.multiply(BASE).add(BigInteger.valueOf(index));
        }

        // Os 144 bits são estruturados da seguinte forma (little-endian byte array):
        byte[] bytes = bigInt.toByteArray();
        byte[] padBytes = new byte[18];

        // Copia os bytes garantindo 18 bytes
        int srcPos = Math.max(0, bytes.length - 18);
        int destPos = Math.max(0, 18 - bytes.length);
        int length = Math.min(bytes.length, 18);
        System.arraycopy(bytes, srcPos, padBytes, destPos, length);

        // Extrai MatchID (primeiros 8 bytes), OutcomeID (próximos 8 bytes), Token (últimos 2 bytes)
        BigInteger matchId = extractBigInteger(padBytes, 0, 8);
        BigInteger outcomeId = extractBigInteger(padBytes, 8, 8);
        int token = extractInt(padBytes, 16, 2);

        log.debug("Share code '{}' decodificado: matchId={}, outcomeId={}, token={}",
                shareCode, matchId, outcomeId, token);

        return DecodedShareCode.builder()
                .matchId(matchId)
                .outcomeId(outcomeId)
                .token(token)
                .originalCode(shareCode)
                .build();
    }

    private static BigInteger extractBigInteger(byte[] bytes, int offset, int length) {
        byte[] sub = new byte[length];
        System.arraycopy(bytes, offset, sub, 0, length);
        return new BigInteger(1, sub);
    }

    private static int extractInt(byte[] bytes, int offset, int length) {
        int result = 0;
        for (int i = 0; i < length; i++) {
            result = (result << 8) | (bytes[offset + i] & 0xFF);
        }
        return result;
    }
}
