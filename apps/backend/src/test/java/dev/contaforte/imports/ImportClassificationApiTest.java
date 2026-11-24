package dev.contaforte.imports;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Classificação dentro da importação (M4.4): sugestão por linha na prévia, troca antes de confirmar e reversão ao desfazer. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ImportClassificationApiTest extends ApiTestSupport {

    // ---------------------------------------------------------------- apoio

    private static byte[] fixture(String name) {
        String dir = System.getenv("FIXTURES_DIR");
        try {
            return Files.readAllBytes(Path.of(dir != null ? dir : "../../datasets/synthetic/imports").resolve(name));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Map previa(Pessoa p, String nome, String conta) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fixture(nome)) {
            @Override
            public String getFilename() {
                return nome;
            }
        });
        body.add("accountId", conta);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(p.token());
        var r = rest.exchange("/api/v1/imports", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private List<Map> linhas(Pessoa p, Object id) {
        return (List<Map>) call(HttpMethod.GET, "/api/v1/imports/" + id + "/items?size=200", null, p).getBody().get("items");
    }

    private Map linha(Pessoa p, Object id, String trecho) {
        return linhas(p, id).stream().filter(i -> String.valueOf(i.get("description")).contains(trecho)).findFirst().orElseThrow();
    }

    private Map confirmar(Pessoa p, Object id) {
        var r = call(HttpMethod.POST, "/api/v1/imports/" + id + "/confirm", null, p);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private Map transacaoDe(Pessoa p, Object importId, String trecho) {
        List<Map> itens = (List<Map>) call(HttpMethod.GET, "/api/v1/transactions?importId=" + importId + "&size=100", null, p).getBody().get("items");
        return itens.stream().filter(t -> String.valueOf(t.get("description")).contains(trecho)).findFirst().orElseThrow();
    }

    private ResponseStatus escolher(Pessoa p, Object importId, Object itemId, Map<String, Object> corpo) {
        var r = call(HttpMethod.PATCH, "/api/v1/imports/" + importId + "/items/" + itemId, corpo, p);
        return new ResponseStatus(HttpStatus.valueOf(r.getStatusCode().value()), r.getBody());
    }

    private record ResponseStatus(HttpStatus status, Map body) {}

    private void ensinarCafe(Pessoa p, String conta, String categoria, int n) {
        for (int i = 0; i < n; i++) {
            criada(p, Map.of("type", "EXPENSE", "accountId", conta, "amount", "8.00", "description", "PIX ENVIADO Café da Praça", "categoryId", categoria));
        }
    }

    // ---------------------------------------------------------------- prévia

    @Test
    void aPreviaClassificaAsLinhasPorRegraPadraoSemCriarNada() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);

        Map p = previa(ana, "conta-corrente-1.ofx", conta);

        assertThat(p).containsEntry("newRows", 12).containsEntry("autoClassified", 9).containsEntry("suggested", 0);
        Map posto = (Map) linha(ana, p.get("id"), "POSTO").get("classification");
        assertThat(posto).containsEntry("decision", "AUTO").containsEntry("source", "DEFAULT_RULE").containsEntry("categoryDecision", "AUTO").containsEntry("chosen", false);
        assertThat(posto.get("categoryId")).isEqualTo(categoriaPadrao(ana, "TRANSPORT_FUEL"));
        assertThat(((Number) posto.get("confidence")).doubleValue()).isEqualTo(0.9);
        Map mercado = (Map) linha(ana, p.get("id"), "MERCADO EXEMPLO").get("classification");
        assertThat(mercado).containsEntry("decision", "NONE");
        assertThat(mercado.get("categoryId")).isNull();
        assertThat(call(HttpMethod.GET, "/api/v1/transactions", null, ana).getBody().get("totalElements")).as("a prévia não cria transação").isEqualTo(0);
        assertThat(call(HttpMethod.GET, "/api/v1/imports/" + p.get("id"), null, ana).getBody()).containsEntry("autoClassified", 9);
    }

    @Test
    void regraDoUsuarioEFavorecidoAprendidoEntramNaPrevia() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String mercado = categoriaPadrao(ana, "FOOD_GROCERIES");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        call(HttpMethod.POST, "/api/v1/categorization-rules", Map.of("matchType", "CONTAINS", "pattern", "mercado exemplo", "categoryId", mercado), ana);
        ensinarCafe(ana, conta, restaurante, 2);   // 2 decisões: 2/3, só sugere

        Map p = previa(ana, "conta-corrente-1.ofx", conta);

        assertThat(p).containsEntry("autoClassified", 10).containsEntry("suggested", 1);
        Map porRegra = (Map) linha(ana, p.get("id"), "MERCADO EXEMPLO").get("classification");
        assertThat(porRegra).containsEntry("source", "RULE").containsEntry("decision", "AUTO").containsEntry("categoryId", mercado);
        Map cafe = (Map) linha(ana, p.get("id"), "CAF").get("classification");
        assertThat(cafe).containsEntry("source", "RECIPIENT").containsEntry("decision", "SUGGEST").containsEntry("categoryId", restaurante);
        assertThat(((Number) cafe.get("confidence")).doubleValue()).isEqualTo(0.66);
    }

    @Test
    void linhasDuplicadasEInvalidasNaoTemClassificacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        confirmar(ana, previa(ana, "conta-corrente-1.ofx", conta).get("id"));
        Map p = previa(ana, "conta-corrente-2.ofx", conta);

        List<Map> dups = linhas(ana, p.get("id")).stream().filter(i -> "DUPLICATE".equals(i.get("status"))).toList();

        assertThat(dups).hasSize(4);
        assertThat(dups).allSatisfy(i -> assertThat(i).doesNotContainKey("classification"));
    }

    // ---------------------------------------------------------------- confirmação

    @Test
    void confirmarCriaCadaTransacaoNoEstadoQueAPreviaMostrou() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        ensinarCafe(ana, conta, restaurante, 2);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);

        confirmar(ana, p.get("id"));

        Map posto = transacaoDe(ana, p.get("id"), "POSTO");
        assertThat(posto).containsEntry("classificationStatus", "AUTO").containsEntry("classificationSource", "DEFAULT_RULE");
        assertThat(posto.get("categoryId")).isEqualTo(categoriaPadrao(ana, "TRANSPORT_FUEL"));
        Map cafe = transacaoDe(ana, p.get("id"), "CAF");
        assertThat(cafe.get("categoryId")).as("sugestão não é aplicada").isNull();
        assertThat(cafe).containsEntry("classificationStatus", "SUGGESTED").containsEntry("suggestedCategoryId", restaurante);
        assertThat(transacaoDe(ana, p.get("id"), "MERCADO EXEMPLO")).containsEntry("classificationStatus", "NONE");
        // classificação automática nunca ensina: o favorecido do café continua com os 2 votos de antes
        Map favorecidos = call(HttpMethod.GET, "/api/v1/pix-recipients", null, ana).getBody();
        assertThat(((List<Map>) favorecidos.get("items"))).hasSize(1);
        assertThat((Map) ((Map) ((Map) ((List<Map>) favorecidos.get("items")).get(0).get("expense")).get("category"))).containsEntry("votes", 2);
        assertThat(call(HttpMethod.GET, "/api/v1/classification/review/count", null, ana).getBody()).containsEntry("suggested", 1);
    }

    @Test
    void aEscolhaDoUsuarioNaPreviaVenceOMotorEEnsinaNaConfirmacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        String familia = contextoPadrao(ana, "FAMILY");
        ensinarCafe(ana, conta, restaurante, 2);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        Map cafe = linha(ana, p.get("id"), "CAF");
        Map posto = linha(ana, p.get("id"), "POSTO");

        var r = escolher(ana, p.get("id"), cafe.get("id"), Map.of("categoryId", lazer, "contextId", familia));
        var s = escolher(ana, p.get("id"), posto.get("id"), Map.of("categoryId", restaurante));   // contra a regra padrão

        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat((Map) r.body().get("classification")).containsEntry("chosen", true).containsEntry("chosenCategoryId", lazer).containsEntry("decision", "SUGGEST");
        assertThat(s.status()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/transactions", null, ana).getBody().get("totalElements")).as("escolher não cria nada").isEqualTo(2);

        confirmar(ana, p.get("id"));

        Map tc = transacaoDe(ana, p.get("id"), "CAF");
        assertThat(tc).containsEntry("categoryId", lazer).containsEntry("contextId", familia).containsEntry("classificationStatus", "CONFIRMED").containsEntry("classificationSource", "USER");
        assertThat(tc.get("suggestedCategoryId")).isNull();
        Map tp = transacaoDe(ana, p.get("id"), "POSTO");
        assertThat(tp).containsEntry("categoryId", restaurante).containsEntry("classificationStatus", "CONFIRMED");
        // o café aprendeu: 2 votos antigos em restaurante e 1 novo em lazer
        Map exp = (Map) ((List<Map>) call(HttpMethod.GET, "/api/v1/pix-recipients?q=cafe", null, ana).getBody().get("items")).get(0).get("expense");
        assertThat((Map) exp.get("category")).containsEntry("id", restaurante).containsEntry("votes", 2).containsEntry("totalVotes", 3);
        assertThat((Map) exp.get("context")).containsEntry("id", familia).containsEntry("votes", 1);
        // feedback: o café tinha sugestão e foi corrigido; o posto tinha regra automática e foi corrigido
        List<Map<String, Object>> fb = jdbc.queryForList("select kind, prediction_source from classification_feedback where user_id = ? order by prediction_source", ana.id());
        assertThat(fb).hasSize(2);
        assertThat(fb).allSatisfy(f -> assertThat(f).containsEntry("kind", "CORRECTED"));
        assertThat(fb.stream().map(f -> f.get("prediction_source")).toList()).containsExactly("DEFAULT_RULE", "RECIPIENT");
    }

    @Test
    void aceitarASugestaoNaPreviaGravaFeedbackDeConfirmacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        ensinarCafe(ana, conta, restaurante, 2);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);

        escolher(ana, p.get("id"), linha(ana, p.get("id"), "CAF").get("id"), Map.of("categoryId", restaurante));
        confirmar(ana, p.get("id"));

        assertThat(jdbc.queryForObject("select kind from classification_feedback where user_id = ?", String.class, ana.id())).isEqualTo("CONFIRMED");
        Map exp = (Map) ((List<Map>) call(HttpMethod.GET, "/api/v1/pix-recipients", null, ana).getBody().get("items")).get(0).get("expense");
        assertThat((Map) exp.get("category")).containsEntry("votes", 3);
    }

    @Test
    void escolherNenhumaCategoriaCriaATransacaoSemClassificacaoEResetVoltaAoMotor() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        Map posto = linha(ana, p.get("id"), "POSTO");
        Map aluguel = linha(ana, p.get("id"), "ALUGUEL");

        Map nenhuma = new java.util.HashMap();
        nenhuma.put("categoryId", null);
        nenhuma.put("contextId", null);
        assertThat(escolher(ana, p.get("id"), posto.get("id"), nenhuma).status()).isEqualTo(HttpStatus.OK);
        escolher(ana, p.get("id"), aluguel.get("id"), Map.of("categoryId", categoriaPadrao(ana, "HOUSING_RENT")));
        var volta = escolher(ana, p.get("id"), aluguel.get("id"), Map.of("reset", true));
        assertThat((Map) volta.body().get("classification")).containsEntry("chosen", false).containsEntry("decision", "AUTO");

        confirmar(ana, p.get("id"));

        assertThat(transacaoDe(ana, p.get("id"), "POSTO")).containsEntry("classificationStatus", "NONE");
        assertThat(transacaoDe(ana, p.get("id"), "POSTO").get("categoryId")).isNull();
        assertThat(transacaoDe(ana, p.get("id"), "ALUGUEL")).containsEntry("classificationStatus", "AUTO");
    }

    // ---------------------------------------------------------------- desfazer reverte o aprendizado

    @Test
    void desfazerAImportacaoTiraOQueEnsinouEMarcaOFeedbackComoRevertido() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        ensinarCafe(ana, conta, restaurante, 2);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        escolher(ana, p.get("id"), linha(ana, p.get("id"), "CAF").get("id"), Map.of("categoryId", lazer));            // corrige uma sugestão
        escolher(ana, p.get("id"), linha(ana, p.get("id"), "JOANA").get("id"), Map.of("categoryId", lazer));          // favorecido novo
        confirmar(ana, p.get("id"));
        assertThat(((Number) call(HttpMethod.GET, "/api/v1/pix-recipients", null, ana).getBody().get("totalElements")).intValue()).isEqualTo(2);

        var r = call(HttpMethod.POST, "/api/v1/imports/" + p.get("id") + "/rollback", null, ana);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map favorecidos = call(HttpMethod.GET, "/api/v1/pix-recipients", null, ana).getBody();
        assertThat(((List<Map>) favorecidos.get("items"))).as("o favorecido que só existia por esta importação sumiu").hasSize(1);
        Map exp = (Map) ((List<Map>) favorecidos.get("items")).get(0).get("expense");
        assertThat((Map) exp.get("category")).containsEntry("id", restaurante).containsEntry("votes", 2).containsEntry("totalVotes", 2);   // de volta ao que era
        assertThat(jdbc.queryForObject("select count(*) from classification_feedback where user_id = ? and reverted_at is not null", Integer.class, ana.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from classification_feedback where user_id = ?", Integer.class, ana.id())).as("o registro permanece").isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/api/v1/classification/review/count", null, ana).getBody()).containsEntry("suggested", 0);
    }

    @Test
    void reimportarDepoisDeDesfazerEnsinaDeNovoSemDuplicarVotos() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        Map p1 = previa(ana, "conta-corrente-1.ofx", conta);
        escolher(ana, p1.get("id"), linha(ana, p1.get("id"), "JOANA").get("id"), Map.of("categoryId", lazer));
        confirmar(ana, p1.get("id"));
        call(HttpMethod.POST, "/api/v1/imports/" + p1.get("id") + "/rollback", null, ana);

        Map p2 = previa(ana, "conta-corrente-1.ofx", conta);
        escolher(ana, p2.get("id"), linha(ana, p2.get("id"), "JOANA").get("id"), Map.of("categoryId", lazer));
        confirmar(ana, p2.get("id"));

        Map f = ((List<Map>) call(HttpMethod.GET, "/api/v1/pix-recipients", null, ana).getBody().get("items")).get(0);
        assertThat((Map) ((Map) f.get("income")).get("category")).containsEntry("votes", 1);
    }

    // ---------------------------------------------------------------- erros e isolamento

    @Test
    void errosAoEscolherNaPrevia() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        confirmar(ana, previa(ana, "conta-corrente-1.ofx", conta).get("id"));
        Map p = previa(ana, "conta-corrente-2.ofx", conta);
        Map nova = linhas(ana, p.get("id")).stream().filter(i -> "NEW".equals(i.get("status"))).findFirst().orElseThrow();
        Map dup = linhas(ana, p.get("id")).stream().filter(i -> "DUPLICATE".equals(i.get("status"))).findFirst().orElseThrow();
        var bCat = call(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Só da Bia"), bia).getBody();

        assertThat(escolher(ana, p.get("id"), dup.get("id"), Map.of("categoryId", cat)).status()).as("duplicada").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(escolher(ana, p.get("id"), UUID.randomUUID(), Map.of("categoryId", cat)).status()).as("linha inexistente").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(escolher(ana, p.get("id"), nova.get("id"), Map.of("categoryId", UUID.randomUUID().toString())).status()).as("categoria inexistente").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(escolher(ana, p.get("id"), nova.get("id"), Map.of("categoryId", bCat.get("id"))).status()).as("categoria de outro usuário").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(escolher(bia, p.get("id"), nova.get("id"), Map.of("categoryId", cat)).status()).as("importação de outro usuário").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(escolher(null, p.get("id"), nova.get("id"), Map.of("categoryId", cat)).status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        confirmar(ana, p.get("id"));
        assertThat(escolher(ana, p.get("id"), nova.get("id"), Map.of("categoryId", cat)).status()).as("depois de confirmar").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void oBancoRecusaClassificacaoDeLinhaInconsistente() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        UUID item = UUID.fromString((String) linha(ana, p.get("id"), "MERCADO EXEMPLO").get("id"));
        UUID cat = UUID.fromString(categoriaPadrao(ana, "FOOD_GROCERIES"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update import_items set engine_category_id = ? where id = ?", cat, item))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);   // alvo sem decisão, origem e confiança
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update import_items set chosen_category_id = ? where id = ?", cat, item))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);   // escolha sem a marca de escolhida
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update import_items set engine_category_id = ?, engine_category_decision = 'TALVEZ', engine_decision = 'AUTO', engine_source = 'RULE', engine_confidence = 1 where id = ?", cat, item))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update import_items set engine_category_id = ?, engine_category_decision = 'SUGGEST', engine_decision = 'AUTO', engine_source = 'RULE', engine_confidence = 0.6 where id = ?", cat, item))
                .as("resumo AUTO com dimensão só sugerida").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update import_items set engine_category_id = ?, engine_category_decision = 'AUTO', engine_decision = 'AUTO', engine_source = 'RULE', engine_confidence = 1 where id = ?", cat, item);
    }

    @Test
    void categoriaDeOutroUsuarioNaLinhaEhRecusadaPeloBanco() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        UUID item = UUID.fromString((String) linha(ana, p.get("id"), "MERCADO EXEMPLO").get("id"));
        UUID daBia = UUID.fromString((String) call(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Só da Bia"), bia).getBody().get("id"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update import_items set chosen = true, chosen_category_id = ? where id = ?", daBia, item))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void auditoriaDaImportacaoNaoCarregaDescricaoNemFavorecido() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        escolher(ana, p.get("id"), linha(ana, p.get("id"), "JOANA").get("id"), Map.of("categoryId", categoriaPadrao(ana, "LEISURE_EVENTS")));
        confirmar(ana, p.get("id"));
        call(HttpMethod.POST, "/api/v1/imports/" + p.get("id") + "/rollback", null, ana);

        String tudo = String.join("\n", jdbc.queryForList("select action || ' ' || resource || ' ' || detail::text from audit_logs where actor_id = ?", String.class, ana.id())).toLowerCase();

        assertThat(tudo).contains("import_rolled_back").contains("learningreverted").doesNotContain("joana").doesNotContain("exemplo").doesNotContain("padaria");
    }
}
