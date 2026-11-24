package dev.contaforte.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Apaga as prévias vencidas (24 h). Minimização de dados: uma prévia esquecida não fica guardando linhas de extrato. */
@Component
class ImportCleanup {

    private static final Logger log = LoggerFactory.getLogger(ImportCleanup.class);

    private final ImportService service;

    ImportCleanup(ImportService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${contaforte.imports.cleanup-interval:PT1H}", initialDelayString = "PT5M")
    void run() {
        try {
            int n = service.purgeExpiredPreviews();
            if (n > 0) log.info("Prévias de importação vencidas removidas: {}", n);
        } catch (RuntimeException e) {
            log.error("Falha na limpeza das prévias vencidas", e);
        }
    }
}
