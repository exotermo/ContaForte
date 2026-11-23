package dev.contaforte.imports.parse;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Formatos de data aceitos no CSV. Escolhidos de uma lista fechada (nunca um padrão livre digitado pelo usuário).
 * {@code AUTO} reconhece qualquer um deles; com barra, a ordem é sempre dia/mês (convenção brasileira).
 * Data sem hora vira 12:00 de São Paulo; com hora, vale o horário de São Paulo (ou o fuso explícito, no formato ISO).
 */
public enum CsvDateFormat {
    AUTO(null, false),
    DMY_SLASH('/', false),      // 10/03/2026
    DMY_SLASH_SHORT('/', false), // 10/03/26
    DMY_DASH('-', false),       // 10-03-2026
    DMY_DOT('.', false),        // 10.03.2026
    YMD_DASH('-', true),        // 2026-03-10
    YMD_SLASH('/', true);       // 2026/03/10

    private final Character sep;
    private final boolean yearFirst;

    CsvDateFormat(Character sep, boolean yearFirst) {
        this.sep = sep;
        this.yearFirst = yearFirst;
    }

    /** @throws DateTimeException se o texto não é uma data neste formato */
    Instant parse(String raw) {
        String s = raw.strip();
        if (s.length() < 6 || s.length() > 40) throw new DateTimeException("tamanho");
        int cut = indexOfTimeStart(s);
        String datePart = cut < 0 ? s : s.substring(0, cut).strip();
        String timePart = cut < 0 ? null : s.substring(cut + (s.charAt(cut) == 'T' ? 1 : 0)).strip();

        char separator = datePart.indexOf('/') >= 0 ? '/' : datePart.indexOf('-') >= 0 ? '-' : '.';
        String[] t = split3(datePart, separator);
        boolean ymd = t[0].length() == 4;
        if (this != AUTO) {
            if (separator != sep || ymd != yearFirst) throw new DateTimeException("formato");
            if (this == DMY_SLASH_SHORT ? t[2].length() != 2 : (!yearFirst && t[2].length() != 4)) throw new DateTimeException("ano");
        }
        int year;
        int month;
        int day;
        if (ymd) {
            year = num(t[0]);
            month = num(t[1]);
            day = num(t[2]);
        } else {
            day = num(t[0]);
            month = num(t[1]);
            year = num(t[2]);
            if (t[2].length() == 2) year += year < 70 ? 2000 : 1900;
            else if (t[2].length() != 4) throw new DateTimeException("ano");
        }
        LocalDate date = LocalDate.of(year, month, day);
        if (timePart == null || timePart.isEmpty()) return DateRules.noonSp(date);
        return withTime(date, timePart);
    }

    private static Instant withTime(LocalDate date, String time) {
        ZoneOffset zone = null;
        String t = time;
        if (t.endsWith("Z") || t.endsWith("z")) {
            zone = ZoneOffset.UTC;
            t = t.substring(0, t.length() - 1);
        } else {
            int sign = Math.max(t.lastIndexOf('+'), t.lastIndexOf('-'));
            if (sign > 0) {
                zone = ZoneOffset.of(t.substring(sign));
                t = t.substring(0, sign);
            }
        }
        String[] p = t.strip().split(":", -1);
        if (p.length < 2 || p.length > 3) throw new DateTimeException("hora");
        int sec = 0;
        if (p.length == 3) {
            String sp = p[2];
            int dot = sp.indexOf('.');
            sec = num(dot < 0 ? sp : sp.substring(0, dot));
        }
        LocalDateTime ldt = LocalDateTime.of(date, LocalTime.of(num(p[0]), num(p[1]), sec));
        return zone != null ? ldt.toInstant(zone) : ldt.atZone(DateRules.SP).toInstant();
    }

    /** Onde começa a hora (depois de um espaço ou de um "T"), ou -1. A data tem no mínimo 6 caracteres. */
    private static int indexOfTimeStart(String s) {
        for (int i = 6; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == 'T') return i;
        }
        return -1;
    }

    private static String[] split3(String s, char sep) {
        int a = s.indexOf(sep);
        int b = a < 0 ? -1 : s.indexOf(sep, a + 1);
        if (a <= 0 || b < 0 || s.indexOf(sep, b + 1) >= 0) throw new DateTimeException("partes");
        return new String[] {s.substring(0, a), s.substring(a + 1, b), s.substring(b + 1)};
    }

    private static int num(String s) {
        if (s.isEmpty() || s.length() > 4) throw new DateTimeException("número");
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw new DateTimeException("número");
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
