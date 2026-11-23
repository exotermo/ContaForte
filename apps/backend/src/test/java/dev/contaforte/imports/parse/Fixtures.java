package dev.contaforte.imports.parse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Acesso às fixtures sintéticas versionadas em datasets/synthetic/imports (FIXTURES_DIR no container do `cf test`). */
final class Fixtures {

    /** "Hoje" fixo: as fixtures são de março de 2026 e o cenário de data futura usa 2099. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);

    private Fixtures() {
    }

    static byte[] bytes(String name) {
        String dir = System.getenv("FIXTURES_DIR");
        Path path = Path.of(dir != null ? dir : "../../datasets/synthetic/imports").resolve(name);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("fixture não encontrada: " + path.toAbsolutePath(), e);
        }
    }
}
