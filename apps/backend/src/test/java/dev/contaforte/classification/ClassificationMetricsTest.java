package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** Métricas da classificação (M4.6): nascem em 0, contam o que o motor decidiu e o feedback, e nunca levam dado pessoal. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ClassificationMetricsTest extends ApiTestSupport {

    @Autowired MeterRegistry metrics;

    private double decisoes(String source, String decision) {
        return metrics.counter("contaforte.classifications", "source", source, "decision", decision).count();
    }

    private double feedback(String kind) {
        return metrics.counter("contaforte.classification.feedback", "kind", kind).count();
    }

    @Test
    void todasAsSeriesNascemEm0ComRotulosFechados() {
        for (String s : new String[] {"RULE", "RECIPIENT", "DEFAULT_RULE"}) {
            for (String d : new String[] {"AUTO", "SUGGEST"}) {
                assertThat(metrics.find("contaforte.classifications").tag("source", s).tag("decision", d).counter()).as(s + "/" + d).isNotNull();
            }
        }
        assertThat(metrics.find("contaforte.classifications").tag("source", "NONE").tag("decision", "NONE").counter()).isNotNull();
        assertThat(metrics.find("contaforte.classification.feedback").tag("kind", "CONFIRMED").counter()).isNotNull();
        assertThat(metrics.find("contaforte.classification.feedback").tag("kind", "CORRECTED").counter()).isNotNull();
    }

    @Test
    void nenhumaSerieTemRotuloComDadoPessoal() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "9.00", "description", "PIX ENVIADO JOANA SEGREDINHO"));
        call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 0, "expectedSuggest", 0), ana);

        List<String> tags = metrics.getMeters().stream().filter(m -> m.getId().getName().startsWith("contaforte.classif"))
                .flatMap(m -> m.getId().getTags().stream()).filter(t -> !t.getKey().equals("application")).map(t -> t.getKey() + "=" + t.getValue()).toList();   // "application" é etiqueta comum de toda métrica

        assertThat(tags).isNotEmpty().allSatisfy(t -> assertThat(t).matches("(source|decision|kind)=[A-Z_]+"));
        assertThat(String.join(" ", tags).toLowerCase()).doesNotContain("joana").doesNotContain("segredinho");
    }

    @Test
    void reclassificarContaCadaLinhaPelaDecisaoDoMotor() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        for (int i = 0; i < 2; i++) criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "8.00", "description", "PIX ENVIADO CAFE DA PRACA", "categoryId", restaurante));
        criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "8.00", "description", "PIX ENVIADO CAFE DA PRACA"));         // sugestão por favorecido
        criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "99.00", "description", "COMPRA POSTO ABC"));                  // regra padrão
        criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "9.00", "description", "LOJA QUALQUER"));                      // desconhecida
        double padraoAuto = decisoes("DEFAULT_RULE", "AUTO"), favorecidoSugestao = decisoes("RECIPIENT", "SUGGEST"), nenhuma = decisoes("NONE", "NONE");

        call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 1, "expectedSuggest", 1), ana);

        assertThat(decisoes("DEFAULT_RULE", "AUTO") - padraoAuto).isEqualTo(1);
        assertThat(decisoes("RECIPIENT", "SUGGEST") - favorecidoSugestao).isEqualTo(1);
        assertThat(decisoes("NONE", "NONE") - nenhuma).isEqualTo(1);
    }

    @Test
    void aPreviaDaImportacaoTambemConta() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        double antes = decisoes("DEFAULT_RULE", "AUTO"), semNada = decisoes("NONE", "NONE");

        // 9 das 12 linhas do extrato são reconhecidas pelas regras padrão; 3 não
        var corpo = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        corpo.add("file", new org.springframework.core.io.ByteArrayResource(fixture("conta-corrente-1.ofx")) {
            @Override
            public String getFilename() {
                return "conta-corrente-1.ofx";
            }
        });
        corpo.add("accountId", conta);
        var h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(ana.token());
        rest.exchange("/api/v1/imports", HttpMethod.POST, new org.springframework.http.HttpEntity<>(corpo, h), Map.class);

        assertThat(decisoes("DEFAULT_RULE", "AUTO") - antes).isEqualTo(9);
        assertThat(decisoes("NONE", "NONE") - semNada).isEqualTo(3);
    }

    @Test
    void feedbackConfirmadoECorrigidoContamSeparado() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        for (int i = 0; i < 2; i++) criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "8.00", "description", "PIX ENVIADO CAFE DA PRACA", "categoryId", restaurante));
        Map a = criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "8.00", "description", "PIX ENVIADO CAFE DA PRACA"));
        Map b = criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "8.00", "description", "PIX ENVIADO CAFE DA PRACA"));
        call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 0, "expectedSuggest", 2), ana);
        double confirmados = feedback("CONFIRMED"), corrigidos = feedback("CORRECTED");

        call(HttpMethod.POST, "/api/v1/transactions/" + a.get("id") + "/classification", Map.of("action", "CONFIRM"), ana);
        call(HttpMethod.POST, "/api/v1/transactions/" + b.get("id") + "/classification", Map.of("action", "CORRECT", "categoryId", lazer), ana);

        assertThat(feedback("CONFIRMED") - confirmados).isEqualTo(1);
        assertThat(feedback("CORRECTED") - corrigidos).isEqualTo(1);
    }

    private static byte[] fixture(String name) {
        String dir = System.getenv("FIXTURES_DIR");
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(dir != null ? dir : "../../datasets/synthetic/imports").resolve(name));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
