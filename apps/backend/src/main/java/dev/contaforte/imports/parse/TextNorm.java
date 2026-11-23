package dev.contaforte.imports.parse;

import java.text.Normalizer;
import java.util.Locale;

/** Limpeza e normalização de texto de extrato. Tudo linear: nada de regex com retrocesso. */
public final class TextNorm {

    private TextNorm() {
    }

    /** Remove caracteres de controle, junta espaços repetidos, apara e corta em {@code max} caracteres. Vazio vira nulo. */
    public static String clean(String s, int max) {
        if (s == null) return null;
        StringBuilder out = new StringBuilder(Math.min(s.length(), max + 16));
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {   // inclui tab, quebra de linha e espaços Unicode
                pendingSpace = out.length() > 0;
                continue;
            }
            if (Character.isISOControl(cp) || cp == 0xFFFD) continue;           // controle (NUL, BEL...) e byte inválido somem
            int need = Character.charCount(cp) + (pendingSpace ? 1 : 0);
            if (out.length() + need > max) break;                               // nunca parte um par substituto ao meio
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        String r = out.toString().trim();
        return r.isEmpty() ? null : r;
    }

    /**
     * Chave de comparação: minúsculas, sem acento, só letras e números separados por um espaço. É a base da
     * deduplicação por descrição (ADR-016): "PADARIA  São João!" e "padaria sao joao" dão a mesma chave.
     */
    public static String key(String s) {
        if (s == null || s.isEmpty()) return "";
        String d = Normalizer.normalize(s.length() > 2000 ? s.substring(0, 2000) : s, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(d.length());
        boolean pendingSpace = false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;   // acentos soltos pela decomposição
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && out.length() > 0) out.append(' ');
                pendingSpace = false;
                out.append(Character.toLowerCase(c));
            } else {
                pendingSpace = true;
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
