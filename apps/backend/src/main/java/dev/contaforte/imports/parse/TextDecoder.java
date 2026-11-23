package dev.contaforte.imports.parse;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodifica o extrato para texto. Extratos brasileiros vêm em UTF-8 (com ou sem BOM) ou Windows-1252/ISO-8859-1;
 * arquivos binários (zip, pdf, imagens) são recusados antes de virar "texto".
 */
final class TextDecoder {

    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private TextDecoder() {
    }

    /** {@code hint} (charset declarado no cabeçalho, se houver) vale quando o arquivo não é UTF-8 válido nem tem BOM. */
    static String decode(byte[] b, Charset hint) {
        if (startsWith(b, 0xEF, 0xBB, 0xBF)) return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        if (startsWith(b, 0xFF, 0xFE)) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (startsWith(b, 0xFE, 0xFF)) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        rejectBinary(b);
        if (hint != null && !StandardCharsets.UTF_8.equals(hint)) return new String(b, hint);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, hint != null ? hint : WINDOWS_1252);
        }
    }

    /** Zip, PDF, executável ou muitos bytes de controle: não é um extrato em texto. */
    static void rejectBinary(byte[] b) {
        if (startsWith(b, 'P', 'K', 3, 4) || startsWith(b, '%', 'P', 'D', 'F') || startsWith(b, 0x7F, 'E', 'L', 'F')
                || startsWith(b, 'M', 'Z') || startsWith(b, 0x89, 'P', 'N', 'G') || startsWith(b, 0xFF, 0xD8, 0xFF)) {
            throw notText();
        }
        int sample = Math.min(b.length, 8192);
        int suspicious = 0;
        for (int i = 0; i < sample; i++) {
            int v = b[i] & 0xFF;
            if (v == 0) throw notText();
            if (v < 0x20 && v != '\t' && v != '\n' && v != '\r' && v != 0x0C) suspicious++;
        }
        if (suspicious * 50 > sample) throw notText();   // mais de 2% de controle
    }

    private static ImportParseException notText() {
        return new ImportParseException(ImportParseException.Reason.NOT_TEXT,
                "O arquivo não é um extrato em texto (OFX ou CSV).");
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((b[i] & 0xFF) != prefix[i]) return false;
        return true;
    }
}
