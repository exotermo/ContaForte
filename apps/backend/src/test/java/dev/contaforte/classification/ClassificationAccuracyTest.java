package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.contaforte.classification.ClassificationEngine.Subject;
import dev.contaforte.transactions.TransactionType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Precisão do motor num conjunto SINTÉTICO com verdade conhecida (datasets/synthetic/classification, gerado por
 * tools/gen-synthetic/gen_classification.py). Reproduz o fluxo como o produto o usaria: o motor classifica cada transação;
 * quando só sugere ou não sabe, a simulação faz o usuário confirmar a verdade (é assim que os favorecidos aprendem);
 * decisões AUTOMÁTICAS nunca ensinam e nunca são corrigidas (pior caso para a precisão). Meta do ADR-017: ao menos 95%
 * das decisões automáticas corretas.
 */
class ClassificationAccuracyTest {

    record Case(String kind, String description, TransactionType type, String category, String context) {
    }

    record Outcome(Case c, Result result) {
    }

    private static UUID id(String prefix, String code) {
        return code == null ? null : UUID.nameUUIDFromBytes((prefix + code).getBytes());
    }

    private static JsonNode load() {
        String dir = System.getenv("FIXTURES_DIR");
        Path path = (dir != null ? Path.of(dir).getParent() : Path.of("../../datasets/synthetic")).resolve("classification/cases.json");
        try {
            return new ObjectMapper().readTree(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("conjunto sintético não encontrado: " + path.toAbsolutePath(), e);
        }
    }

    private static List<Rule> userRules(JsonNode doc) {
        List<Rule> rules = new ArrayList<>();
        int i = 0;
        for (JsonNode n : doc.get("userRules")) {
            rules.add(new Rule(UUID.nameUUIDFromBytes(("user" + i).getBytes()), MatchType.valueOf(n.get("matchType").asText()), n.get("pattern").asText(),
                    TransactionType.valueOf(n.get("type").asText()), id("cat:", n.get("category").asText()),
                    n.get("context").isNull() ? null : id("ctx:", n.get("context").asText()), 100, Instant.ofEpochSecond(i++), Source.RULE));
        }
        return rules;
    }

    private static List<Case> cases(JsonNode doc) {
        List<Case> out = new ArrayList<>();
        for (JsonNode n : doc.get("cases")) {
            out.add(new Case(n.get("kind").asText(), n.get("description").asText(), TransactionType.valueOf(n.get("type").asText()),
                    n.get("category").asText(), n.get("context").isNull() ? null : n.get("context").asText()));
        }
        return out;
    }

    /** Reproduz o fluxo todo e devolve o que o motor decidiu em cada caso, na ordem. */
    private static List<Outcome> replay(JsonNode doc, List<Rule> userRules, List<Case> cases) {
        List<Rule> defaults = DefaultRules.load(code -> id("cat:", code));
        Map<String, RecipientKnowledge> known = new HashMap<>();
        List<Outcome> out = new ArrayList<>();
        for (Case c : cases) {
            CounterpartyName.Result who = CounterpartyName.extract(c.description());
            String key = who == null ? null : who.key() + "|" + (c.type() == TransactionType.INCOME ? "IN" : "OUT");
            RecipientKnowledge knowledge = key == null ? RecipientKnowledge.EMPTY : known.getOrDefault(key, RecipientKnowledge.EMPTY);

            Result r = ClassificationEngine.classify(new Subject(c.description(), null, c.type()), userRules, defaults, knowledge);
            out.add(new Outcome(c, r));

            if (key != null) {   // o usuário revisa o que não foi aplicado sozinho e confirma a verdade: só isso ensina
                UUID cat = r.category().decision() == Decision.AUTO ? null : id("cat:", c.category());
                UUID ctx = r.context().decision() == Decision.AUTO ? null : id("ctx:", c.context());
                known.put(key, knowledge.withVote(cat, ctx));
            }
        }
        return out;
    }

    @Test
    void precisaoDasDecisoesAutomaticasNoConjuntoSintetico() {
        JsonNode doc = load();
        List<Outcome> outcomes = replay(doc, userRules(doc), cases(doc));

        int catAuto = 0, catAutoOk = 0, catSuggest = 0, catSuggestOk = 0, catNone = 0;
        int ctxAuto = 0, ctxAutoOk = 0;
        Map<String, int[]> porOrigem = new HashMap<>();   // origem → {auto, corretas}
        for (Outcome o : outcomes) {
            Part cat = o.result().category();
            boolean ok = cat.id() != null && cat.id().equals(id("cat:", o.c().category()));
            switch (cat.decision()) {
                case AUTO -> {
                    catAuto++;
                    if (ok) catAutoOk++;
                    int[] s = porOrigem.computeIfAbsent(cat.source().name(), k -> new int[2]);
                    s[0]++;
                    if (ok) s[1]++;
                }
                case SUGGEST -> {
                    catSuggest++;
                    if (ok) catSuggestOk++;
                }
                case NONE -> catNone++;
            }
            Part ctx = o.result().context();
            if (ctx.decision() == Decision.AUTO) {
                ctxAuto++;
                if (ctx.id().equals(id("ctx:", o.c().context()))) ctxAutoOk++;
            }
        }
        int total = outcomes.size();
        double catPrecision = (double) catAutoOk / catAuto;
        double ctxPrecision = ctxAuto == 0 ? 1 : (double) ctxAutoOk / ctxAuto;
        System.out.printf("CLASSIFICACAO %d casos: categoria AUTO %d (%.1f%%, precisão %.1f%%), SUGERIR %d (acerto %.1f%%), NENHUMA %d; contexto AUTO %d (precisão %.1f%%); por origem %s%n",
                total, catAuto, 100.0 * catAuto / total, 100 * catPrecision, catSuggest, 100.0 * catSuggestOk / Math.max(1, catSuggest), catNone,
                ctxAuto, 100 * ctxPrecision, describe(porOrigem));

        assertThat(catPrecision).as("precisão das categorias aplicadas sozinhas").isGreaterThanOrEqualTo(0.95);
        assertThat(ctxPrecision).as("precisão dos contextos aplicados sozinhos").isGreaterThanOrEqualTo(0.95);
        assertThat((double) catAuto / total).as("cobertura automática mínima (o conjunto não pode ser só abstenção)").isGreaterThanOrEqualTo(0.30);
        assertThat(catNone).as("o motor se abstém do que não conhece").isGreaterThan(0);
    }

    private static String describe(Map<String, int[]> porOrigem) {
        StringBuilder sb = new StringBuilder();
        porOrigem.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(String.format("%s=%d (%.1f%%) ", e.getKey(), e.getValue()[0], 100.0 * e.getValue()[1] / e.getValue()[0])));
        return sb.toString().trim();
    }

    @Test
    void estabelecimentoDesconhecidoNuncaViraAutomaticoENenhumFavorecidoNovoAplicaSozinho() {
        JsonNode doc = load();
        List<Outcome> outcomes = replay(doc, userRules(doc), cases(doc));

        for (Outcome o : outcomes) {
            if (o.c().kind().equals("unknown")) {
                assertThat(o.result()).as(o.c().description()).isEqualTo(Result.NONE);   // não conhece, não chuta
            }
        }
    }

    @Test
    void favorecidoSoAplicaSozinhoDepoisDeSeisConfirmacoesNaMesmaDirecao() {
        JsonNode doc = load();
        List<Rule> defaults = DefaultRules.load(code -> id("cat:", code));
        Map<String, Integer> confirmacoes = new HashMap<>();
        Map<String, RecipientKnowledge> known = new HashMap<>();
        for (Case c : cases(doc)) {
            CounterpartyName.Result who = CounterpartyName.extract(c.description());
            if (who == null) continue;
            String key = who.key() + "|" + c.type();
            RecipientKnowledge k = known.getOrDefault(key, RecipientKnowledge.EMPTY);
            Result r = ClassificationEngine.classify(new Subject(c.description(), null, c.type()), userRules(doc), defaults, k);
            if (r.category().source() == Source.RECIPIENT && r.category().decision() == Decision.AUTO) {
                assertThat(confirmacoes.getOrDefault(key, 0)).as("confirmações de " + key).isGreaterThanOrEqualTo(6);
            }
            known.put(key, k.withVote(id("cat:", c.category()), id("ctx:", c.context())));
            confirmacoes.merge(key, 1, Integer::sum);
        }
    }

    @Test
    void oResultadoNaoDependeDaOrdemDasRegrasDoUsuarioEEhRepetivel() {
        JsonNode doc = load();
        List<Rule> regras = userRules(doc);
        List<Case> casos = cases(doc);

        List<Outcome> a = replay(doc, regras, casos);
        List<Rule> embaralhadas = new ArrayList<>(regras);
        Collections.shuffle(embaralhadas, new Random(99));
        List<Outcome> b = replay(doc, embaralhadas, casos);

        assertThat(b).isEqualTo(a);
    }

    @Test
    void oConjuntoEhFicticioETemOTamanhoEsperado() {
        JsonNode doc = load();

        assertThat(doc.get("fictional").asBoolean()).isTrue();
        assertThat(doc.get("cases").size()).isGreaterThan(500);
    }
}
