package dev.contaforte.imports.parse;

import java.math.BigDecimal;

/**
 * Lê valores de extrato como texto, sem ponto flutuante. Aceita sinal (-, +, parênteses e sinal no fim), "R$" e
 * separadores de milhar. Nunca arredonda: mais de 2 casas decimais é erro. O resultado vem sempre com 2 casas.
 */
final class AmountParser {

    private static final int MAX_INTEGER_DIGITS = 12;   // NUMERIC(14,2)

    /** Valor ilegível: vira linha inválida, não erro do arquivo. */
    static final class BadAmount extends RuntimeException {
        BadAmount(String message) {
            super(message, null, false, false);
        }
    }

    private AmountParser() {
    }

    /** CSV brasileiro: com vírgula, ela é o decimal; com os dois separadores, o último decide; "12.5" é decimal. */
    static BigDecimal parseBr(String raw) {
        return parse(raw, false);
    }

    /** OFX: o ponto é o decimal; se só houver vírgula, ela é o decimal (alguns bancos brasileiros exportam assim). */
    static BigDecimal parseOfx(String raw) {
        return parse(raw, true);
    }

    private static BigDecimal parse(String raw, boolean dotIsDecimal) {
        if (raw == null) throw new BadAmount("valor ausente");
        String s = raw.strip();
        if (s.isEmpty()) throw new BadAmount("valor ausente");
        if (s.length() > 40) throw new BadAmount("valor inválido");

        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {   // contabilidade: (123,45)
            negative = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1).strip();
        } else if (s.startsWith("+")) {
            s = s.substring(1).strip();
        }
        if (s.endsWith("-")) {                          // "123,45-"
            negative = true;
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.regionMatches(true, 0, "R$", 0, 2)) {
            s = s.substring(2).strip();
            if (s.startsWith("-") && !negative) {       // "R$ -10,00" (mas "-R$ -10" e "--5" continuam inválidos)
                negative = true;
                s = s.substring(1).strip();
            }
        }
        if (s.isEmpty()) throw new BadAmount("valor ausente");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && c != '.' && c != ',') throw new BadAmount("valor inválido");
        }

        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        String integer;
        String fraction = "";
        if (lastDot >= 0 && lastComma >= 0) {
            // os dois: o último separador é o decimal, o outro agrupa milhares
            char dec = lastDot > lastComma ? '.' : ',';
            char grp = dec == '.' ? ',' : '.';
            int p = s.lastIndexOf(dec);
            integer = groupless(s.substring(0, p), grp);
            fraction = s.substring(p + 1);
            if (s.indexOf(dec) != p) throw new BadAmount("valor inválido");   // dois decimais
        } else if (lastComma >= 0) {
            if (s.indexOf(',') != lastComma) throw new BadAmount("valor inválido");
            integer = s.substring(0, lastComma);
            fraction = s.substring(lastComma + 1);
        } else if (lastDot >= 0) {
            boolean single = s.indexOf('.') == lastDot;
            String after = s.substring(lastDot + 1);
            if (single && (dotIsDecimal || after.length() <= 2)) {
                integer = s.substring(0, lastDot);
                fraction = after;
            } else {
                integer = groupless(s, '.');            // "1.234.567": milhares
            }
        } else {
            integer = s;
        }

        if (integer.isEmpty()) integer = "0";
        if (!allDigits(integer) || !allDigits(fraction)) throw new BadAmount("valor inválido");
        // zeros à direita não contam ("10.500" é 10,50), mas centavos a mais não são arredondados
        String f = fraction;
        while (f.length() > 2 && f.endsWith("0")) f = f.substring(0, f.length() - 1);
        if (f.length() > 2) throw new BadAmount("mais de 2 casas decimais");
        integer = integer.replaceFirst("^0+(?=\\d)", "");
        if (integer.length() > MAX_INTEGER_DIGITS) throw new BadAmount("valor acima do limite");

        BigDecimal value = new BigDecimal(integer + "." + (f + "00").substring(0, 2));
        return negative ? value.negate() : value;
    }

    /** Remove o separador de milhar, exigindo grupos de 3 dígitos depois do primeiro ("1.234.567"). */
    private static String groupless(String s, char sep) {
        if (s.indexOf(sep) < 0) return s;
        String[] parts = s.split("\\" + sep, -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            // o primeiro grupo tem de 1 a 3 dígitos e não começa com zero ("0.001" são 3 casas decimais, não mil)
            if (i == 0 ? (parts[i].isEmpty() || parts[i].length() > 3 || parts[i].charAt(0) == '0') : parts[i].length() != 3) {
                throw new BadAmount("valor inválido");
            }
            out.append(parts[i]);
        }
        return out.toString();
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
