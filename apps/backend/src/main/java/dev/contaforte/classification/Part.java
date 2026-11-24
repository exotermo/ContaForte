package dev.contaforte.classification;

import java.util.UUID;

/** A decisão para UMA dimensão (categoria ou contexto): o quê, com que confiança, vinda de quem e o que fazer. */
public record Part(UUID id, Confidence confidence, Source source, Decision decision) {

    public static final Part NONE = new Part(null, null, null, Decision.NONE);

    static Part of(UUID id, Confidence confidence, Source source) {
        return new Part(id, confidence, source, confidence.decision());
    }

    public boolean decided() {
        return decision != Decision.NONE;
    }
}
