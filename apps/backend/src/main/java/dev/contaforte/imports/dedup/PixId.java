package dev.contaforte.imports.dedup;

/**
 * Identificador fim a fim de um PIX ("EndToEndId"), quando aparece na descrição do extrato: 32 caracteres, começando
 * com {@code E} (pagamento) ou {@code D} (devolução), seguido de 8 dígitos do participante, 12 dígitos de data e hora
 * (AAAAMMDDHHMM) e 11 caracteres alfanuméricos. É único entre bancos, então serve de chave de deduplicação entre fontes.
 * Letras maiúsculas e minúsculas são DIFERENTES no identificador: ele é devolvido exatamente como veio (nada de dobrar a
 * caixa, que poderia unir dois identificadores distintos), e só o prefixo maiúsculo E/D é aceito.
 * A busca é linear: nenhuma expressão regular.
 */
public final class PixId {

    private static final int LENGTH = 32;

    private PixId() {
    }

    /** O primeiro identificador PIX no texto, exatamente como escrito, ou nulo. */
    public static String extract(String text) {
        if (text == null || text.length() < LENGTH) return null;
        int n = text.length();
        for (int i = 0; i + LENGTH <= n; i++) {
            char c = text.charAt(i);
            if (c != 'E' && c != 'D') continue;
            if (i > 0 && isAlphanumeric(text.charAt(i - 1))) continue;             // tem de começar uma "palavra"
            if (i + LENGTH < n && isAlphanumeric(text.charAt(i + LENGTH))) continue; // e terminar nela
            if (matches(text, i + 1)) return text.substring(i, i + LENGTH);
        }
        return null;
    }

    /** 8 dígitos + 12 dígitos de data/hora plausíveis + 11 alfanuméricos. */
    private static boolean matches(String s, int from) {
        for (int k = 0; k < 20; k++) if (!isDigit(s.charAt(from + k))) return false;
        int month = (s.charAt(from + 12) - '0') * 10 + (s.charAt(from + 13) - '0');   // AAAA MM DD HH MM depois dos 8 do participante
        int day = (s.charAt(from + 14) - '0') * 10 + (s.charAt(from + 15) - '0');
        int hour = (s.charAt(from + 16) - '0') * 10 + (s.charAt(from + 17) - '0');
        int minute = (s.charAt(from + 18) - '0') * 10 + (s.charAt(from + 19) - '0');
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59) return false;
        for (int k = 20; k < 31; k++) if (!isAlphanumeric(s.charAt(from + k))) return false;
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlphanumeric(char c) {
        return isDigit(c) || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
