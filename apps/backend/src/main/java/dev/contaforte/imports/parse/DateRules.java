package dev.contaforte.imports.parse;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Regras de data compartilhadas pelos parsers. O "dia" é sempre o de São Paulo (mesma regra do resto do sistema). */
final class DateRules {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant EARLIEST = LocalDate.of(2000, 1, 1).atStartOfDay(SP).toInstant();

    private DateRules() {
    }

    /** Data sem hora vira 12:00 em São Paulo, para o dia nunca "pular" ao converter para UTC. */
    static Instant noonSp(LocalDate d) {
        return d.atTime(12, 0).atZone(SP).toInstant();
    }

    /** Motivo da recusa, ou nulo se a data é aceitável (entre 2000 e amanhã, como as transações manuais). */
    static String problem(Instant when, Clock clock) {
        if (when.isBefore(EARLIEST)) return "data anterior a 2000";
        if (when.isAfter(Instant.now(clock).plus(1, ChronoUnit.DAYS))) return "data no futuro";
        return null;
    }
}
