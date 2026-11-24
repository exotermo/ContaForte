package dev.contaforte.classification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.contaforte.transactions.TransactionType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Carrega as regras padrão do sistema ({@code classification/default-rules.json}), trocando os códigos por ids. */
public final class DefaultRules {

    static final String RESOURCE = "classification/default-rules.json";

    private DefaultRules() {
    }

    /** Códigos de categoria citados no arquivo (para conferir contra o catálogo). */
    public static Set<String> categoryCodes() {
        Set<String> codes = new HashSet<>();
        for (JsonNode n : read().get("rules")) codes.add(n.get("category").asText());
        return codes;
    }

    /** @param categoryIdByCode id da categoria padrão do sistema pelo código; nulo se o código não existe */
    public static List<Rule> load(Function<String, UUID> categoryIdByCode) {
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode n : read().get("rules")) {
            String pattern = n.get("pattern").asText();
            MatchType match = MatchType.valueOf(n.get("match").asText());
            TransactionType type = TransactionType.valueOf(n.get("type").asText());
            String code = n.get("category").asText();
            UUID category = categoryIdByCode.apply(code);
            if (category == null) throw new IllegalStateException("regra padrão cita categoria inexistente: " + code);
            if (!seen.add(match + "|" + pattern + "|" + type)) throw new IllegalStateException("regra padrão repetida: " + pattern);
            UUID id = UUID.nameUUIDFromBytes(("default|" + match + "|" + pattern + "|" + type).getBytes(StandardCharsets.UTF_8));
            rules.add(new Rule(id, match, pattern, type, category, null, 0, Instant.EPOCH, Source.DEFAULT_RULE));
        }
        return List.copyOf(rules);
    }

    private static JsonNode read() {
        try (InputStream in = DefaultRules.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("recurso ausente: " + RESOURCE);
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("não consegui ler " + RESOURCE, e);
        }
    }
}
