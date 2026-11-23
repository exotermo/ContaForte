package dev.contaforte.imports.parse;

import dev.contaforte.imports.ImportFormat;
import java.util.Optional;

/**
 * Descobre o formato pelo CONTEÚDO, nunca pelo nome ou pelo Content-Type (que o cliente controla). Devolve vazio para
 * binários e para texto que não parece nem OFX nem CSV.
 */
public final class ContentSniffer {

    private ContentSniffer() {
    }

    public static Optional<ImportFormat> detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return Optional.empty();
        String head;
        try {
            // decodifica só o começo (com BOM e binário tratados pelo decoder): UTF-16 com BOM é texto; zip e pdf não são
            head = TextDecoder.decode(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 8192)), null);
        } catch (ImportParseException e) {
            return Optional.empty();
        }
        if (containsIgnoreCase(head, "OFXHEADER") || containsIgnoreCase(head, "<OFX")) return Optional.of(ImportFormat.OFX);
        boolean lines = head.indexOf('\n') >= 0 || head.indexOf('\r') >= 0;
        boolean delimited = head.indexOf(';') >= 0 || head.indexOf(',') >= 0 || head.indexOf('\t') >= 0 || head.indexOf('|') >= 0;
        return lines && delimited ? Optional.of(ImportFormat.CSV) : Optional.empty();
    }

    private static boolean containsIgnoreCase(String s, String what) {
        int max = s.length() - what.length();
        for (int i = 0; i <= max; i++) if (s.regionMatches(true, i, what, 0, what.length())) return true;
        return false;
    }
}
