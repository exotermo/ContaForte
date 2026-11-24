package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import dev.contaforte.users.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SuppressWarnings({"rawtypes", "unchecked"})
class ClassificationApiTest extends ApiTestSupport {

    private static final String RULES = "/api/v1/categorization-rules";

    // ---------------------------------------------------------------- apoio

    private Map pix(Pessoa p, String conta, String tipo, String nome, String valor, String categoriaId) {
        Map<String, Object> b = corpo(tipo, conta, valor);
        b.put("description", (tipo.equals("EXPENSE") ? "PIX ENVIADO " : "PIX RECEBIDO ") + nome);
        if (categoriaId != null) b.put("categoryId", categoriaId);
        return criada(p, b);
    }

    private Map tx(Pessoa p, String id) {
        return call(HttpMethod.GET, "/api/v1/transactions/" + id, null, p).getBody();
    }

    private Map decidir(Pessoa p, String id, Map<String, Object> body, HttpStatus esperado) {
        var r = call(HttpMethod.POST, "/api/v1/transactions/" + id + "/classification", body, p);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(esperado);
        return r.getBody();
    }

    private Map regra(Pessoa p, Map<String, Object> body) {
        var r = call(HttpMethod.POST, RULES, body, p);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private Map favorecidos(Pessoa p, String query) {
        return call(HttpMethod.GET, "/api/v1/pix-recipients" + query, null, p).getBody();
    }

    private Map unico(Map pagina) {
        List<Map> items = (List<Map>) pagina.get("items");
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    /** Ensina o favorecido: {@code n} despesas PIX já com categoria escolhida pelo usuário (cada uma é uma decisão). */
    private void ensinar(Pessoa p, String conta, String nome, String categoriaId, int n) {
        for (int i = 0; i < n; i++) pix(p, conta, "EXPENSE", nome, "10.00", categoriaId);
    }

    // ---------------------------------------------------------------- acesso e isolamento

    @Test
    void semTokenEhNaoAutorizadoEServicoEDispositivoRecebem403() {
        String[][] rotas = {{"GET", RULES}, {"POST", RULES}, {"POST", RULES + "/test"}, {"GET", "/api/v1/pix-recipients"},
                {"GET", "/api/v1/classification/review"}, {"GET", "/api/v1/classification/review/count"},
                {"POST", "/api/v1/classification/reclassify/preview"}, {"POST", "/api/v1/classification/reclassify"}};
        for (String[] r : rotas) {
            HttpMethod m = HttpMethod.valueOf(r[0]);
            assertThat(status(m, r[1], Map.of(), null)).as("sem token " + r[1]).isEqualTo(HttpStatus.UNAUTHORIZED);
            for (Role role : new Role[] {Role.SERVICE, Role.DEVICE}) {
                assertThat(status(m, r[1], Map.of(), pessoa(role))).as(role + " " + r[1]).isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
    }

    @Test
    void recursosDeOutroUsuarioSaoInvisiveis() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map r = regra(ana, Map.of("matchType", "CONTAINS", "pattern", "restaurante", "categoryId", cat));
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", cat);
        String favorecido = (String) unico(favorecidos(ana, "")).get("id");

        assertThat(status(HttpMethod.GET, RULES + "/" + r.get("id"), null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.PATCH, RULES + "/" + r.get("id"), Map.of("enabled", false), bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.DELETE, RULES + "/" + r.get("id"), null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, "/api/v1/pix-recipients/" + favorecido, null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.DELETE, "/api/v1/pix-recipients/" + favorecido, null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, "/api/v1/transactions/" + t.get("id") + "/classification", Map.of("action", "CORRECT", "categoryId", cat), bia)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listasDeOutroUsuarioVemVazias() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        regra(ana, Map.of("matchType", "CONTAINS", "pattern", "restaurante", "categoryId", cat));
        pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", cat);

        assertThat(getList(RULES, bia)).isEmpty();
        assertThat(favorecidos(bia, "").get("totalElements")).isEqualTo(0);
        assertThat(((Number) call(HttpMethod.GET, "/api/v1/classification/review/count", null, bia).getBody().get("uncategorized")).longValue()).isZero();
    }

    @Test
    void categoriaDeOutroUsuarioEhRecusadaNasRegras() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        var minha = call(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Só da Bia"), bia);
        assertThat(minha.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "mercado", "categoryId", minha.getBody().get("id")), ana)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- regras: CRUD e validação

    @Test
    void criaListaEditaEApagaUmaRegra() {
        Pessoa ana = pessoa();
        String mercado = categoriaPadrao(ana, "FOOD_GROCERIES");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");

        Map a = regra(ana, Map.of("matchType", "CONTAINS", "pattern", "  Mercadinho do Zé!  ", "categoryId", mercado, "priority", 50));
        Map b = regra(ana, Map.of("matchType", "STARTS_WITH", "pattern", "academia", "transactionType", "EXPENSE", "categoryId", restaurante, "priority", 10));

        assertThat(a.get("pattern")).isEqualTo("mercadinho do ze");   // guardado normalizado
        assertThat(a.get("enabled")).isEqualTo(true);
        assertThat(getList(RULES, ana)).extracting(r -> r.get("id")).containsExactly(b.get("id"), a.get("id"));   // prioridade menor primeiro

        var editada = call(HttpMethod.PATCH, RULES + "/" + a.get("id"), Map.of("priority", 5, "enabled", false, "transactionType", "EXPENSE"), ana);
        assertThat(editada.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(editada.getBody()).containsEntry("priority", 5).containsEntry("enabled", false).containsEntry("transactionType", "EXPENSE");

        Map semTipo = new HashMap();
        semTipo.put("transactionType", null);   // null explícito limpa o tipo
        assertThat(call(HttpMethod.PATCH, RULES + "/" + a.get("id"), semTipo, ana).getBody().get("transactionType")).isNull();

        assertThat(status(HttpMethod.DELETE, RULES + "/" + a.get("id"), null, ana)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(HttpMethod.GET, RULES + "/" + a.get("id"), null, ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getList(RULES, ana)).hasSize(1);
    }

    @Test
    void validacoesDasRegras() {
        Pessoa ana = pessoa();
        String cat = categoriaPadrao(ana, "FOOD_GROCERIES");
        Map<String, Object> ok = Map.of("matchType", "CONTAINS", "pattern", "mercado", "categoryId", cat);
        regra(ana, ok);

        assertThat(status(HttpMethod.POST, RULES, ok, ana)).as("condição repetida").isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "MERCADO!", "categoryId", cat), ana)).as("repetida depois de normalizar").isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "mercado", "transactionType", "EXPENSE", "categoryId", cat), ana)).as("tipo diferente é outra condição").isEqualTo(HttpStatus.CREATED);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "ab", "categoryId", cat), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "!!!", "categoryId", cat), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "farmacia"), ana)).as("sem alvo").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "farmacia", "transactionType", "TRANSFER", "categoryId", cat), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "REGEX", "pattern", "farmacia", "categoryId", cat), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "farmacia", "categoryId", cat, "priority", 10001), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "farmacia", "categoryId", UUID.randomUUID().toString()), ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "x".repeat(201), "categoryId", cat), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void editarNaoPodeDeixarARegraSemAlvoNemRepetirOutra() {
        Pessoa ana = pessoa();
        String cat = categoriaPadrao(ana, "FOOD_GROCERIES");
        Map a = regra(ana, Map.of("matchType", "CONTAINS", "pattern", "mercado", "categoryId", cat));
        Map b = regra(ana, Map.of("matchType", "CONTAINS", "pattern", "feira", "categoryId", cat));

        Map semCategoria = new HashMap();
        semCategoria.put("categoryId", null);
        assertThat(status(HttpMethod.PATCH, RULES + "/" + a.get("id"), semCategoria, ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.PATCH, RULES + "/" + b.get("id"), Map.of("pattern", "Mercado"), ana)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(HttpMethod.PATCH, RULES + "/" + b.get("id"), Map.of("pattern", "feira livre"), ana)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void limiteDe200RegrasPorUsuario() {
        Pessoa ana = pessoa();
        String cat = categoriaPadrao(ana, "FOOD_GROCERIES");
        for (int i = 0; i < 200; i++) {
            jdbc.update("insert into categorization_rules (user_id, match_type, pattern, category_id) values (?, 'CONTAINS', ?, ?)", ana.id(), "padrao " + i, UUID.fromString(cat));
        }

        assertThat(status(HttpMethod.POST, RULES, Map.of("matchType", "CONTAINS", "pattern", "mais uma", "categoryId", cat), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---------------------------------------------------------------- testar um texto

    @Test
    void testarUmTextoMostraAOrigemEAConfiancaSemGravarNada() {
        Pessoa ana = pessoa();
        String mercado = categoriaPadrao(ana, "FOOD_GROCERIES");
        regra(ana, Map.of("matchType", "CONTAINS", "pattern", "mercadinho do ze", "categoryId", mercado));

        Map minha = call(HttpMethod.POST, RULES + "/test", Map.of("description", "COMPRA MERCADINHO DO ZE 12/03", "type", "EXPENSE"), ana).getBody();
        Map padrao = call(HttpMethod.POST, RULES + "/test", Map.of("description", "COMPRA POSTO ABC", "type", "EXPENSE"), ana).getBody();
        Map nenhuma = call(HttpMethod.POST, RULES + "/test", Map.of("description", "LOJA QUALQUER", "type", "EXPENSE"), ana).getBody();

        assertThat((Map) minha.get("category")).containsEntry("source", "RULE").containsEntry("decision", "AUTO").containsEntry("id", mercado);
        assertThat(((Number) ((Map) minha.get("category")).get("confidence")).doubleValue()).isEqualTo(1.0);
        assertThat((Map) padrao.get("category")).containsEntry("source", "DEFAULT_RULE").containsEntry("decision", "AUTO");
        assertThat(((Number) ((Map) padrao.get("category")).get("confidence")).doubleValue()).isEqualTo(0.9);
        assertThat(nenhuma.get("decision")).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, ana.id())).isZero();
    }

    // ---------------------------------------------------------------- favorecidos: aprender, listar, esquecer

    @Test
    void decisoesManuaisEnsinamOFavorecidoPorDirecao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        String outras = categoriaPadrao(ana, "INCOME_OTHER");

        ensinar(ana, conta, "JOANA EXEMPLO", lazer, 3);
        pix(ana, conta, "INCOME", "JOANA EXEMPLO", "50.00", outras);

        Map f = unico(favorecidos(ana, ""));
        assertThat(f.get("displayName")).isEqualTo("JOANA EXEMPLO");
        assertThat(f.get("transactionCount")).isEqualTo(4);
        Map despesa = (Map) ((Map) f.get("expense")).get("category");
        assertThat(despesa).containsEntry("id", lazer).containsEntry("votes", 3).containsEntry("totalVotes", 3).containsEntry("decision", "SUGGEST");
        assertThat(((Number) despesa.get("confidence")).doubleValue()).isEqualTo(0.75);   // 3/(3+1)
        Map receita = (Map) ((Map) f.get("income")).get("category");
        assertThat(receita).containsEntry("id", outras).containsEntry("votes", 1);
        assertThat(((Number) receita.get("confidence")).doubleValue()).isEqualTo(0.5);
        assertThat(f.get("averageAmount")).isEqualTo("20.00");   // (10+10+10+50)/4
    }

    @Test
    void transacaoCriadaSemCategoriaNaoEnsinaNada() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");

        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", null);

        assertThat(t.get("classificationStatus")).isEqualTo("NONE");
        assertThat(favorecidos(ana, "").get("totalElements")).isEqualTo(0);
    }

    @Test
    void buscaPorNomeIgnoraAcentoCaixaECoringas() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        ensinar(ana, conta, "Padaria São João", cat, 1);
        ensinar(ana, conta, "JOANA EXEMPLO", cat, 1);

        assertThat(((List) favorecidos(ana, "?q=SAO joao").get("items"))).hasSize(1);
        assertThat(((List) favorecidos(ana, "?q=joa").get("items"))).hasSize(2);   // "joao" e "joana"
        assertThat(((List) favorecidos(ana, "?q=%").get("items"))).as("% não é coringa").isEmpty();
        assertThat(((List) favorecidos(ana, "?q=_").get("items"))).as("_ não é coringa").isEmpty();
        assertThat(((List) favorecidos(ana, "?q=zzz").get("items"))).isEmpty();
        assertThat(((List) favorecidos(ana, "").get("items"))).hasSize(2);
    }

    @Test
    void esquecerOFavorecidoApagaOAprendizadoENaoMexeNasTransacoes() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", cat);
        String id = (String) unico(favorecidos(ana, "")).get("id");

        assertThat(status(HttpMethod.DELETE, "/api/v1/pix-recipients/" + id, null, ana)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(favorecidos(ana, "").get("totalElements")).isEqualTo(0);
        assertThat(status(HttpMethod.GET, "/api/v1/pix-recipients/" + id, null, ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tx(ana, (String) t.get("id")).get("categoryId")).isEqualTo(cat);   // a transação continua classificada
        assertThat(jdbc.queryForObject("select count(*) from pix_recipient_category_votes v join pix_recipients r on r.id = v.recipient_id where r.user_id = ?", Integer.class, ana.id())).isZero();
    }

    // ---------------------------------------------------------------- reclassificar em duas fases

    @Test
    void reclassificarAplicaOQueAPreviaMostrouENuncaTocaNoQueOUsuarioDecidiu() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        ensinar(ana, conta, "JOANA EXEMPLO", lazer, 6);        // 6 decisões: confiança 6/7, aplica sozinho
        ensinar(ana, conta, "CAFE DA PRACA", restaurante, 2);  // 2 decisões: 2/3, só sugere

        Map auto = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "30.00", null);
        Map sugerida = pix(ana, conta, "EXPENSE", "CAFE DA PRACA", "8.00", null);
        Map porRegra = criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "200.00", "description", "COMPRA POSTO ABC COMBUSTIVEL"));
        Map desconhecida = criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "99.00", "description", "LOJA QUALQUER"));
        Map decidida = pix(ana, conta, "EXPENSE", "BAR DO ZE", "5.00", restaurante);   // o usuário já escolheu a categoria desta

        Map previa = call(HttpMethod.POST, "/api/v1/classification/reclassify/preview", null, ana).getBody();
        assertThat(previa).containsEntry("eligible", 4).containsEntry("auto", 2).containsEntry("suggest", 1).containsEntry("none", 1).containsEntry("truncated", false);
        assertThat((List) previa.get("sample")).hasSize(3);
        assertThat(tx(ana, (String) auto.get("id")).get("classificationStatus")).as("a prévia não grava").isEqualTo("NONE");

        assertThat(status(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 1, "expectedSuggest", 1), ana)).as("resultado diferente do da prévia").isEqualTo(HttpStatus.CONFLICT);
        var aplicada = call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 2, "expectedSuggest", 1), ana);
        assertThat(aplicada.getBody()).containsEntry("auto", 2).containsEntry("suggest", 1).containsEntry("unchanged", 1);

        Map a = tx(ana, (String) auto.get("id"));
        assertThat(a).containsEntry("categoryId", lazer).containsEntry("classificationStatus", "AUTO").containsEntry("classificationSource", "RECIPIENT");
        assertThat(((Number) a.get("classificationConfidence")).doubleValue()).isEqualTo(0.85);   // 6/7 = 0,857 gravado truncado
        Map s = tx(ana, (String) sugerida.get("id"));
        assertThat(s.get("categoryId")).as("sugerida não aplica").isNull();
        assertThat(s).containsEntry("suggestedCategoryId", restaurante).containsEntry("classificationStatus", "SUGGESTED");
        assertThat(tx(ana, (String) porRegra.get("id"))).containsEntry("classificationSource", "DEFAULT_RULE").containsEntry("classificationStatus", "AUTO");
        assertThat(tx(ana, (String) desconhecida.get("id"))).containsEntry("classificationStatus", "NONE");
        Map d = tx(ana, (String) decidida.get("id"));
        assertThat(d).containsEntry("categoryId", restaurante).containsEntry("classificationStatus", "CONFIRMED");   // intocada

        Map depois = call(HttpMethod.POST, "/api/v1/classification/reclassify/preview", null, ana).getBody();
        assertThat(depois).containsEntry("auto", 0).containsEntry("suggest", 1).containsEntry("none", 1);   // idempotente
    }

    @Test
    void reclassificarSemNadaParaFazerEOk() {
        Pessoa ana = pessoa();

        Map previa = call(HttpMethod.POST, "/api/v1/classification/reclassify/preview", null, ana).getBody();

        assertThat(previa).containsEntry("eligible", 0).containsEntry("auto", 0).containsEntry("suggest", 0);
        assertThat(status(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 0, "expectedSuggest", 0), ana)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", -1, "expectedSuggest", 0), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- fila de revisão e decisão

    @Test
    void filaDeRevisaoEContadoresRefletemSugeridasESemCategoria() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        ensinar(ana, conta, "CAFE DA PRACA", cat, 2);
        Map sugerida = pix(ana, conta, "EXPENSE", "CAFE DA PRACA", "8.00", null);
        Map semCategoria = criada(ana, Map.of("type", "EXPENSE", "accountId", conta, "amount", "9.00", "description", "LOJA QUALQUER"));
        criada(ana, Map.of("type", "TRANSFER", "accountId", conta, "counterpartAccountId", conta(ana, "Poupança", "SAVINGS_ACCOUNT", "0.00"), "amount", "1.00"));
        call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 0, "expectedSuggest", 1), ana);

        Map contagem = call(HttpMethod.GET, "/api/v1/classification/review/count", null, ana).getBody();
        Map fila = call(HttpMethod.GET, "/api/v1/classification/review", null, ana).getBody();

        assertThat(contagem).containsEntry("suggested", 1).containsEntry("uncategorized", 1);
        assertThat(((List<Map>) fila.get("items")).stream().map(i -> i.get("id")).toList()).containsExactlyInAnyOrder(sugerida.get("id"), semCategoria.get("id"));   // transferência não entra
    }

    @Test
    void confirmarUmaSugestaoAplicaEnsinaEGravaFeedback() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        ensinar(ana, conta, "CAFE DA PRACA", cat, 2);
        Map t = pix(ana, conta, "EXPENSE", "CAFE DA PRACA", "8.00", null);
        call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 0, "expectedSuggest", 1), ana);

        Map confirmada = decidir(ana, (String) t.get("id"), Map.of("action", "CONFIRM"), HttpStatus.OK);

        assertThat(confirmada).containsEntry("categoryId", cat).containsEntry("classificationStatus", "CONFIRMED").containsEntry("classificationSource", "USER");
        assertThat(confirmada.get("suggestedCategoryId")).isNull();
        assertThat(confirmada.get("classificationConfidence")).isNull();
        assertThat(((Map) ((Map) unico(favorecidos(ana, "")).get("expense")).get("category"))).containsEntry("votes", 3);
        List<Map<String, Object>> fb = jdbc.queryForList("select kind, prediction_source, prediction_confidence, model_version from classification_feedback where user_id = ?", ana.id());
        assertThat(fb).hasSize(1);
        assertThat(fb.get(0)).containsEntry("kind", "CONFIRMED").containsEntry("prediction_source", "RECIPIENT").containsEntry("model_version", ClassificationEngine.VERSION);
        assertThat(((Number) fb.get(0).get("prediction_confidence")).doubleValue()).isEqualTo(0.66);   // 2/3 truncado
        assertThat(status(HttpMethod.POST, "/api/v1/transactions/" + t.get("id") + "/classification", Map.of("action", "CONFIRM"), ana)).as("já confirmada").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void corrigirUmaClassificacaoAutomaticaGravaFeedbackDeCorrecaoEMoveOAprendizado() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        ensinar(ana, conta, "JOANA EXEMPLO", lazer, 6);
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "30.00", null);
        call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 1, "expectedSuggest", 0), ana);
        assertThat(tx(ana, (String) t.get("id"))).containsEntry("classificationStatus", "AUTO");

        Map corrigida = decidir(ana, (String) t.get("id"), Map.of("action", "CORRECT", "categoryId", restaurante), HttpStatus.OK);

        assertThat(corrigida).containsEntry("categoryId", restaurante).containsEntry("classificationStatus", "CONFIRMED");
        List<Map<String, Object>> fb = jdbc.queryForList("select kind, previous_category_id, corrected_category_id, prediction_source from classification_feedback where user_id = ?", ana.id());
        assertThat(fb).hasSize(1);
        assertThat(fb.get(0)).containsEntry("kind", "CORRECTED").containsEntry("prediction_source", "RECIPIENT");
        assertThat(fb.get(0).get("previous_category_id").toString()).isEqualTo(lazer);
        assertThat(fb.get(0).get("corrected_category_id").toString()).isEqualTo(restaurante);
        // o voto novo soma (a classificação automática nunca tinha ensinado nada, então não há voto a retirar)
        Map expense = (Map) unico(favorecidos(ana, "")).get("expense");
        assertThat((Map) expense.get("category")).containsEntry("id", lazer).containsEntry("votes", 6);
        assertThat(jdbc.queryForObject("select v.votes from pix_recipient_category_votes v join pix_recipients r on r.id = v.recipient_id "
                + "where r.user_id = ? and v.category_id = ?::uuid", Integer.class, ana.id(), restaurante)).isEqualTo(1);
    }

    @Test
    void mudarADecisaoJaConfirmadaTiraOVotoAntigoEPoeONovo() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String lazer = categoriaPadrao(ana, "LEISURE_EVENTS");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "30.00", lazer);   // confirmada ao lançar: 1 voto em lazer

        decidir(ana, (String) t.get("id"), Map.of("action", "CORRECT", "categoryId", restaurante), HttpStatus.OK);

        Map expense = (Map) unico(favorecidos(ana, "")).get("expense");
        assertThat((Map) expense.get("category")).containsEntry("id", restaurante).containsEntry("votes", 1).containsEntry("totalVotes", 1);
        assertThat(unico(favorecidos(ana, "")).get("transactionCount")).isEqualTo(1);   // a mesma transação não conta duas vezes
        assertThat(jdbc.queryForObject("select count(*) from classification_feedback where user_id = ?", Integer.class, ana.id())).as("não havia previsão do motor").isZero();
    }

    @Test
    void criarRegraJuntoComADecisaoExigePadraoQueCasaComATransacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map t = pix(ana, conta, "EXPENSE", "CAFE DA PRACA", "8.00", null);
        String id = (String) t.get("id");

        decidir(ana, id, Map.of("action", "CORRECT", "categoryId", cat, "createRule", Map.of("matchType", "CONTAINS", "pattern", "outra coisa")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(getList(RULES, ana)).as("nada foi gravado").isEmpty();
        assertThat(tx(ana, id)).containsEntry("classificationStatus", "NONE");

        decidir(ana, id, Map.of("action", "CORRECT", "categoryId", cat, "createRule", Map.of("matchType", "CONTAINS", "pattern", "Café da Praça")), HttpStatus.OK);
        List<Map> regras = getList(RULES, ana);
        assertThat(regras).hasSize(1);
        assertThat(regras.get(0)).containsEntry("pattern", "cafe da praca").containsEntry("transactionType", "EXPENSE").containsEntry("categoryId", cat);

        decidir(ana, id, Map.of("action", "CORRECT", "categoryId", cat, "createRule", Map.of("matchType", "CONTAINS", "pattern", "cafe da praca")), HttpStatus.CONFLICT);
        // e a regra criada passa a valer no teste de texto
        assertThat((Map) call(HttpMethod.POST, RULES + "/test", Map.of("description", "PIX ENVIADO CAFE DA PRACA", "type", "EXPENSE"), ana).getBody().get("category")).containsEntry("source", "RULE");
    }

    @Test
    void erroDeDecisao() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map t = pix(ana, conta, "EXPENSE", "CAFE DA PRACA", "8.00", null);
        Map transf = criada(ana, Map.of("type", "TRANSFER", "accountId", conta, "counterpartAccountId", conta(ana, "Poupança", "SAVINGS_ACCOUNT", "0.00"), "amount", "1.00"));
        String id = (String) t.get("id");

        decidir(ana, id, Map.of("action", "CONFIRM"), HttpStatus.CONFLICT);                                  // nada a confirmar
        decidir(ana, id, Map.of("action", "CORRECT"), HttpStatus.UNPROCESSABLE_ENTITY);                       // nem categoria nem contexto
        decidir(ana, id, Map.of("action", "CORRECT", "categoryId", UUID.randomUUID().toString()), HttpStatus.NOT_FOUND);
        decidir(ana, (String) transf.get("id"), Map.of("action", "CORRECT", "categoryId", cat), HttpStatus.UNPROCESSABLE_ENTITY);   // transferência não é classificada
        decidir(ana, id, Map.of("action", "TALVEZ"), HttpStatus.BAD_REQUEST);
        decidir(ana, id, Map.of(), HttpStatus.BAD_REQUEST);
        var bCat = call(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Só da Bia"), bia).getBody();
        decidir(ana, id, Map.of("action", "CORRECT", "categoryId", bCat.get("id")), HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, "/api/v1/transactions/" + UUID.randomUUID() + "/classification", Map.of("action", "CONFIRM"), ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tx(ana, id)).containsEntry("classificationStatus", "NONE");
    }

    // ---------------------------------------------------------------- edição pelo PATCH de transações

    @Test
    void editarACategoriaPeloPatchEUmaDecisaoDoUsuario() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", null);

        var r = call(HttpMethod.PATCH, "/api/v1/transactions/" + t.get("id"), Map.of("categoryId", cat), ana);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("categoryId", cat).containsEntry("classificationStatus", "CONFIRMED");
        assertThat(((Map) unico(favorecidos(ana, "")).get("expense"))).isNotNull();

        Map limpar = new HashMap();
        limpar.put("categoryId", null);
        var limpa = call(HttpMethod.PATCH, "/api/v1/transactions/" + t.get("id"), limpar, ana);
        assertThat(limpa.getBody()).containsEntry("classificationStatus", "NONE");
        assertThat(limpa.getBody().get("categoryId")).isNull();
        assertThat(favorecidos(ana, "").get("totalElements")).as("o aprendizado foi desfeito e o favorecido sumiu").isEqualTo(0);
    }

    @Test
    void editarUmaTransacaoSugeridaPeloPatchNaoQuebraAsRestricoes() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        String outra = categoriaPadrao(ana, "LEISURE_EVENTS");
        ensinar(ana, conta, "CAFE DA PRACA", cat, 2);
        Map t = pix(ana, conta, "EXPENSE", "CAFE DA PRACA", "8.00", null);
        call(HttpMethod.POST, "/api/v1/classification/reclassify", Map.of("expectedAuto", 0, "expectedSuggest", 1), ana);
        assertThat(tx(ana, (String) t.get("id"))).containsEntry("classificationStatus", "SUGGESTED");

        var r = call(HttpMethod.PATCH, "/api/v1/transactions/" + t.get("id"), Map.of("categoryId", outra), ana);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("categoryId", outra).containsEntry("classificationStatus", "CONFIRMED");
        assertThat(r.getBody().get("suggestedCategoryId")).isNull();
        assertThat(jdbc.queryForObject("select kind from classification_feedback where user_id = ?", String.class, ana.id())).isEqualTo("CORRECTED");
    }

    @Test
    void editarSoOValorOuADataNaoMexeNaClassificacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", cat);

        var r = call(HttpMethod.PATCH, "/api/v1/transactions/" + t.get("id"), Map.of("amount", "12.00"), ana);

        assertThat(r.getBody()).containsEntry("classificationStatus", "CONFIRMED").containsEntry("categoryId", cat);
        assertThat(((Map) ((Map) unico(favorecidos(ana, "")).get("expense")).get("category"))).containsEntry("votes", 1);
    }

    @Test
    void mudarADescricaoDeUmaTransacaoConfirmadaMoveOVotoParaOFavorecidoCerto() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", cat);

        call(HttpMethod.PATCH, "/api/v1/transactions/" + t.get("id"), Map.of("description", "PIX ENVIADO MARIA SOUZA"), ana);

        Map f = unico(favorecidos(ana, ""));
        assertThat(f.get("displayName")).isEqualTo("MARIA SOUZA");   // o favorecido antigo ficou sem nada e sumiu
    }

    // ---------------------------------------------------------------- privacidade

    @Test
    void auditoriaNuncaCarregaNomeDeFavorecidoNemPadraoDeRegra() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        regra(ana, Map.of("matchType", "CONTAINS", "pattern", "segredinho da silva", "categoryId", cat));
        Map t = pix(ana, conta, "EXPENSE", "JOANA SEGREDINHO", "10.00", null);
        decidir(ana, (String) t.get("id"), Map.of("action", "CORRECT", "categoryId", cat, "createRule", Map.of("matchType", "CONTAINS", "pattern", "joana segredinho")), HttpStatus.OK);
        call(HttpMethod.DELETE, "/api/v1/pix-recipients/" + unico(favorecidos(ana, "")).get("id"), null, ana);
        call(HttpMethod.POST, "/api/v1/classification/reclassify/preview", null, ana);

        List<String> detalhes = new ArrayList<>(jdbc.queryForList("select action || ' ' || resource || ' ' || detail::text from audit_logs where actor_id = ?", String.class, ana.id()));

        assertThat(detalhes).isNotEmpty();
        assertThat(String.join("\n", detalhes).toLowerCase()).doesNotContain("segredinho").doesNotContain("joana").doesNotContain("silva");
        assertThat(detalhes.stream().anyMatch(d -> d.contains("CLASSIFICATION_CORRECTED"))).isTrue();
        assertThat(detalhes.stream().anyMatch(d -> d.contains("RECIPIENT_FORGOTTEN"))).isTrue();
    }

    @Test
    void apagarOUsuarioApagaTudoOQueFoiAprendido() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "0.00");
        String cat = categoriaPadrao(ana, "FOOD_RESTAURANT");
        regra(ana, Map.of("matchType", "CONTAINS", "pattern", "restaurante", "categoryId", cat));
        Map t = pix(ana, conta, "EXPENSE", "JOANA EXEMPLO", "10.00", null);
        decidir(ana, (String) t.get("id"), Map.of("action", "CORRECT", "categoryId", cat), HttpStatus.OK);

        jdbc.update("delete from users where id = ?", ana.id());

        assertThat(jdbc.queryForObject("select count(*) from pix_recipients where user_id = ?", Integer.class, ana.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from categorization_rules where user_id = ?", Integer.class, ana.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from classification_feedback where user_id = ?", Integer.class, ana.id())).isZero();
    }
}
