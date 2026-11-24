package dev.contaforte.imports;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Métricas de importação. Só tags de baixa cardinalidade e sem dado pessoal (formato, status e resultado): nunca nome de
 * arquivo, descrição ou valor. Todos os contadores nascem em 0 na partida, senão o Prometheus perderia a primeira
 * contagem de cada série (lição do Milestone 2).
 */
@Component
class ImportMetrics {

    static final String[] FORMATS = {"OFX", "CSV", "UNKNOWN"};
    static final String[] STATUSES = {"PREVIEW", "CONFIRMED", "ROLLED_BACK", "DISCARDED", "EXPIRED", "REJECTED"};
    static final String[] RESULTS = {"NEW", "DUPLICATE", "POSSIBLE_DUPLICATE", "INVALID", "IMPORTED"};

    private final MeterRegistry registry;

    ImportMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String f : FORMATS) {
            for (String s : STATUSES) registry.counter("contaforte.imports", "format", f, "status", s);
        }
        for (String r : RESULTS) registry.counter("contaforte.import.rows", "result", r);
        for (String f : new String[] {"OFX", "CSV"}) preview(f);
    }

    void imports(String format, String status) {
        imports(format, status, 1);
    }

    void imports(String format, String status, int n) {
        if (n > 0) registry.counter("contaforte.imports", "format", format, "status", status).increment(n);
    }

    void rows(String result, int n) {
        if (n > 0) registry.counter("contaforte.import.rows", "result", result).increment(n);
    }

    void previewDuration(String format, Duration d) {
        preview(format).record(d);
    }

    private Timer preview(String format) {
        return Timer.builder("contaforte.import.preview").tag("format", format)
                .publishPercentileHistogram().register(registry);
    }
}
