package dev.contaforte.categories;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import dev.contaforte.users.Role;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SuppressWarnings({"rawtypes", "unchecked"})
class CategoryApiTest extends ApiTestSupport {

    private List<Map> arvore(Pessoa p) {
        return getList("/api/v1/categories", p);
    }

    private Map no(List<Map> arvore, String nome) {
        return arvore.stream().filter(c -> nome.equals(c.get("name"))).findFirst().orElseThrow();
    }

    private List<Map> filhas(Map pai) {
        return (List<Map>) pai.get("children");
    }

    private Object idPadrao(Pessoa p, String nome) {
        return no(arvore(p), nome).get("id");
    }

    private Map criar(Pessoa p, String nome, Object parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", nome);
        if (parentId != null) body.put("parentId", parentId);
        var r = call(HttpMethod.POST, "/api/v1/categories", body, p);
        assertThat(r.getStatusCode()).as("criar %s: %s", nome, r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private HttpStatus tentar(HttpMethod m, String path, Object body, Pessoa p) {
        return HttpStatus.valueOf(call(m, path, body, p).getStatusCode().value());
    }

    // ---------------------------------------------------------------- acesso

    @Test
    void semTokenEhNaoAutorizadoEServicoEDispositivoRecebem403() {
        assertThat(tentar(HttpMethod.GET, "/api/v1/categories", null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tentar(HttpMethod.GET, "/api/v1/contexts", null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (Role role : new Role[] {Role.SERVICE, Role.DEVICE}) {
            Pessoa p = pessoa(role);
            assertThat(tentar(HttpMethod.GET, "/api/v1/categories", null, p)).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(tentar(HttpMethod.GET, "/api/v1/contexts", null, p)).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------- árvore de categorias

    @Test
    void usuarioNovoVeAArvorePadraoEmPortuguesComOsCodigos() {
        List<Map> arvore = arvore(pessoa());

        assertThat(arvore).hasSize(12);
        assertThat(arvore.stream().mapToInt(c -> filhas(c).size()).sum()).isEqualTo(39);
        assertThat(arvore).allSatisfy(c -> {
            assertThat(c.get("system")).isEqualTo(true);
            assertThat(c.get("parentId")).isNull();
            assertThat(c.get("code")).isNotNull();
        });
        Map alimentacao = no(arvore, "Alimentação");
        assertThat(alimentacao.get("code")).isEqualTo("FOOD");
        assertThat(filhas(alimentacao)).extracting(f -> f.get("name"))
                .containsExactly("Delivery", "Lanches e padaria", "Mercado", "Restaurante");   // ordem alfabética pt-BR
        assertThat(filhas(alimentacao)).allSatisfy(f -> assertThat(f.get("parentId")).isEqualTo(alimentacao.get("id")));
    }

    @Test
    void ordenaPorNomeComAcentoNoLugarCerto() {
        List<String> nomes = arvore(pessoa()).stream().map(c -> (String) c.get("name")).toList();
        assertThat(nomes.indexOf("Alimentação")).isLessThan(nomes.indexOf("Compras"));
        assertThat(nomes.indexOf("Educação")).isLessThan(nomes.indexOf("Investimentos"));
        assertThat(nomes).isSortedAccordingTo(java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pt-BR")));
    }

    // ---------------------------------------------------------------- criação de categorias

    @Test
    void criaCategoriaPropriaESubcategoriasSobPadraoESobPropria() {
        Pessoa ana = pessoa();
        Map hobbies = criar(ana, " Hobbies ", null);
        assertThat(hobbies).containsEntry("name", "Hobbies").containsEntry("system", false);
        assertThat(hobbies.get("code")).isNull();
        assertThat(hobbies.get("parentId")).isNull();

        Map pesca = criar(ana, "Pesca", hobbies.get("id"));
        Map cafeteria = criar(ana, "Cafeteria", idPadrao(ana, "Alimentação"));

        List<Map> arvore = arvore(ana);
        assertThat(filhas(no(arvore, "Hobbies"))).extracting(f -> f.get("name")).containsExactly("Pesca");
        assertThat(filhas(no(arvore, "Alimentação"))).extracting(f -> f.get("name")).contains("Cafeteria", "Restaurante");
        assertThat(filhas(no(arvore, "Alimentação")).stream().filter(f -> f.get("id").equals(cafeteria.get("id")))
                .findFirst().orElseThrow()).containsEntry("system", false);
        assertThat(pesca.get("parentId")).isEqualTo(hobbies.get("id"));
    }

    @Test
    void respostaDeCriacaoTemLocation() {
        var r = call(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Presentes"), pessoa());
        assertThat(r.getHeaders().getLocation().getPath()).isEqualTo("/api/v1/categories/" + r.getBody().get("id"));
    }

    @Test
    void terceiroNivelEhRecusadoCom422() {
        Pessoa ana = pessoa();
        Object restaurante = filhas(no(arvore(ana), "Alimentação")).stream()
                .filter(f -> "Restaurante".equals(f.get("name"))).findFirst().orElseThrow().get("id");
        Map propriaPai = criar(ana, "Nível 1", null);
        Map propriaFilha = criar(ana, "Nível 2", propriaPai.get("id"));

        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Japonês", "parentId", restaurante), ana))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Nível 3", "parentId", propriaFilha.get("id")), ana))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void paiDeOutroUsuarioOuInexistenteDa404EIdMalformadoDa400() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        Map daAna = criar(ana, "Segredos da Ana", null);

        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Invasão", "parentId", daAna.get("id")), bia))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "X", "parentId", UUID.randomUUID().toString()), bia))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "X", "parentId", "nao-eh-uuid"), bia))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nomeDuplicadoNoMesmoNivelDa409InclusiveContraAsPadrao() {
        Pessoa ana = pessoa();
        Object alimentacaoId = idPadrao(ana, "Alimentação");

        // contra uma categoria padrão do mesmo nível (sem diferenciar maiúsculas/acento de caixa)
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "ALIMENTAÇÃO"), ana)).isEqualTo(HttpStatus.CONFLICT);
        // contra uma subcategoria padrão do mesmo pai
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "restaurante", "parentId", alimentacaoId), ana))
                .isEqualTo(HttpStatus.CONFLICT);
        // contra uma própria
        criar(ana, "Pets", null);
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "PETS"), ana)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mesmoNomeEmPaisDiferentesOuEmUsuariosDiferentesEPermitido() {
        Pessoa ana = pessoa();
        criar(ana, "Outros gastos", idPadrao(ana, "Saúde"));
        criar(ana, "Outros gastos", idPadrao(ana, "Lazer"));
        criar(pessoa(), "Pets", null);
        criar(pessoa(), "Pets", null);
    }

    @Test
    void validacaoDoNome() {
        Pessoa ana = pessoa();
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "   "), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("parentId", UUID.randomUUID().toString()), ana))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tentar(HttpMethod.POST, "/api/v1/categories", Map.of("name", "x".repeat(81)), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- isolamento e edição

    @Test
    void categoriaPropriaSoAparecePraQuemCriou() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        criar(ana, "Só da Ana", null);

        assertThat(arvore(ana)).extracting(c -> c.get("name")).contains("Só da Ana");
        assertThat(arvore(bia)).extracting(c -> c.get("name")).doesNotContain("Só da Ana").hasSize(12);
    }

    @Test
    void renomeiaCategoriaPropria() {
        Pessoa ana = pessoa();
        Map c = criar(ana, "Velho", null);

        var r = call(HttpMethod.PATCH, "/api/v1/categories/" + c.get("id"), Map.of("name", "Novo"), ana);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("name", "Novo");
        // mudar só a caixa do próprio nome não conflita consigo mesma
        assertThat(tentar(HttpMethod.PATCH, "/api/v1/categories/" + c.get("id"), Map.of("name", "NOVO"), ana)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void categoriaPadraoNaoPodeSerAlteradaDa403() {
        Pessoa ana = pessoa();
        Object id = idPadrao(ana, "Alimentação");

        assertThat(tentar(HttpMethod.PATCH, "/api/v1/categories/" + id, Map.of("name", "Comida"), ana)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(arvore(ana)).extracting(c -> c.get("name")).contains("Alimentação").doesNotContain("Comida");
    }

    @Test
    void naoRenomeiaParaNomeExistenteNemEditaCategoriaDeOutro() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        Map a = criar(ana, "Alfa", null);
        criar(ana, "Beta", null);

        assertThat(tentar(HttpMethod.PATCH, "/api/v1/categories/" + a.get("id"), Map.of("name", "beta"), ana)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tentar(HttpMethod.PATCH, "/api/v1/categories/" + a.get("id"), Map.of("name", "Invadida"), bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tentar(HttpMethod.PATCH, "/api/v1/categories/" + UUID.randomUUID(), Map.of("name", "X"), bia))
                .as("inexistente responde igual a 'de outro usuário'").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(arvore(ana)).extracting(c -> c.get("name")).contains("Alfa").doesNotContain("Invadida");
    }

    // ---------------------------------------------------------------- contextos

    private List<Map> contextos(Pessoa p) {
        return getList("/api/v1/contexts", p);
    }

    private Map criarContexto(Pessoa p, String nome) {
        var r = call(HttpMethod.POST, "/api/v1/contexts", Map.of("name", nome), p);
        assertThat(r.getStatusCode()).as("criar contexto %s: %s", nome, r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @Test
    void usuarioNovoVeOsSeisContextosPadrao() {
        List<Map> padrao = contextos(pessoa());
        assertThat(padrao).extracting(c -> c.get("name"))
                .containsExactlyInAnyOrder("Individual", "Família", "Relacionamento", "Pet", "Trabalho", "Outro");
        assertThat(padrao).extracting(c -> c.get("code"))
                .containsExactlyInAnyOrder("INDIVIDUAL", "FAMILY", "RELATIONSHIP", "PET", "WORK", "OTHER");
        assertThat(padrao).allSatisfy(c -> assertThat(c.get("system")).isEqualTo(true));
    }

    @Test
    void criaRenomeiaEIsolaContextoPropio() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        Map avos = criarContexto(ana, " Avós ");
        assertThat(avos).containsEntry("name", "Avós").containsEntry("system", false);
        assertThat(avos.get("code")).isNull();

        assertThat(call(HttpMethod.PATCH, "/api/v1/contexts/" + avos.get("id"), Map.of("name", "Avós e tios"), ana).getBody())
                .containsEntry("name", "Avós e tios");
        assertThat(contextos(ana)).extracting(c -> c.get("name")).contains("Avós e tios", "Família");
        assertThat(contextos(bia)).extracting(c -> c.get("name")).doesNotContain("Avós e tios").hasSize(6);
        assertThat(tentar(HttpMethod.PATCH, "/api/v1/contexts/" + avos.get("id"), Map.of("name", "Invadido"), bia)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void contextoPadraoNaoPodeSerAlteradoEDuplicadoDa409() {
        Pessoa ana = pessoa();
        Object familia = contextos(ana).stream().filter(c -> "Família".equals(c.get("name"))).findFirst().orElseThrow().get("id");

        assertThat(tentar(HttpMethod.PATCH, "/api/v1/contexts/" + familia, Map.of("name", "Parentes"), ana)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(tentar(HttpMethod.POST, "/api/v1/contexts", Map.of("name", "FAMÍLIA"), ana)).isEqualTo(HttpStatus.CONFLICT);
        criarContexto(ana, "Faculdade");
        assertThat(tentar(HttpMethod.POST, "/api/v1/contexts", Map.of("name", "faculdade"), ana)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tentar(HttpMethod.POST, "/api/v1/contexts", Map.of("name", " "), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- auditoria e contrato

    @Test
    void auditoriaRegistraOEventoMasNuncaONome() {
        Pessoa ana = pessoa();
        Map c = criar(ana, "Categoria Secretíssima", null);
        call(HttpMethod.PATCH, "/api/v1/categories/" + c.get("id"), Map.of("name", "Outro Segredo"), ana);
        Map x = criarContexto(ana, "Contexto Íntimo");
        call(HttpMethod.PATCH, "/api/v1/contexts/" + x.get("id"), Map.of("name", "Contexto Reservado"), ana);

        List<Map<String, Object>> linhas = jdbc.queryForList(
                "select action, resource, detail::text as detail from audit_logs "
                        + "where actor_id = ? and (resource like 'category:%' or resource like 'context:%')", ana.id());
        assertThat(linhas).extracting(l -> l.get("action"))
                .containsExactlyInAnyOrder("CATEGORY_CREATED", "CATEGORY_RENAMED", "CONTEXT_CREATED", "CONTEXT_RENAMED");
        for (var l : linhas) {
            assertThat((String) l.get("detail")).doesNotContain("Secret", "Segredo", "Íntimo", "Reservado");
            assertThat((String) l.get("resource")).doesNotContain("Secret", "Segredo");
        }
    }

    @Test
    void openApiDocumentaOsEndpoints() {
        Map paths = (Map) rest.getForEntity("/api/v1/openapi", Map.class).getBody().get("paths");
        assertThat(paths).containsKeys("/api/v1/categories", "/api/v1/categories/{id}", "/api/v1/contexts", "/api/v1/contexts/{id}");
    }
}
