package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.classification.ClassificationEngine.Subject;
import dev.contaforte.transactions.TransactionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Custo do motor na prévia de uma importação grande (M4.4): 20 mil linhas contra o pior caso de regras (200 do usuário, o
 * máximo permitido, mais as padrão) e muitos favorecidos aprendidos. Guarda contra regressão de desempenho: a importação
 * inteira precisa continuar bem abaixo de 10 s (ADR-015).
 */
class ClassificationPerformanceTest {

    private static UUID id(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes());
    }

    @Test
    void vinteMilLinhasContraOPiorCasoDeRegrasEFavorecidos() {
        List<Rule> user = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            user.add(new Rule(id("u" + i), MatchType.CONTAINS, "padrao numero " + i, TransactionType.EXPENSE, id("cat" + i % 20), null, i, Instant.ofEpochSecond(i), Source.RULE));
        }
        List<Rule> defaults = DefaultRules.load(code -> id(code));
        Map<String, RecipientKnowledge> recipients = new HashMap<>();
        for (int i = 0; i < 2000; i++) recipients.put("favorecido numero " + i + "|OUT", RecipientKnowledge.EMPTY.withVote(id("c" + i % 9), null).withVote(id("c" + i % 9), null));
        String[] textos = {"COMPRA CARTAO SUPERMERCADO BOM PRECO 12/03 SAO PAULO BR", "PIX ENVIADO FAVORECIDO NUMERO 77 ***.123.456-**",
                "TRANSFERENCIA PIX RECEBIDA JOANA EXEMPLO", "COMPRA LOJA QUALQUER 123456", "UBER *TRIP HELP.UBER.COM", "DEBITO AUTOMATICO ENERGIA ELETRICA 0312"};

        ClassificationService noDb = null;
        long t0 = System.nanoTime();
        int decididas = 0;
        for (int n = 0; n < 20_000; n++) {
            String d = textos[n % textos.length] + " " + n;
            CounterpartyName.Result who = CounterpartyName.extract(d);
            RecipientKnowledge k = who == null ? RecipientKnowledge.EMPTY : recipients.getOrDefault(who.key() + "|OUT", RecipientKnowledge.EMPTY);
            Result r = ClassificationEngine.classify(new Subject(d, null, TransactionType.EXPENSE), user, defaults, k);
            if (r.summary().decision() != Decision.NONE) decididas++;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("DESEMPENHO classificar 20000 linhas (200 regras do usuário + %d padrão, 2000 favorecidos): %d ms, %d decididas%n", defaults.size(), ms, decididas);
        assertThat(ms).as("ms para classificar 20 mil linhas").isLessThan(3_000);
        assertThat(decididas).isGreaterThan(0);
    }
}
