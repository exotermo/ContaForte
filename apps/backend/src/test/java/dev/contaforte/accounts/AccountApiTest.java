package dev.contaforte.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import dev.contaforte.transactions.TransactionType;
import dev.contaforte.users.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class AccountApiTest extends ApiTestSupport {

    private Map criar(Pessoa p, String nome, String tipo, Object saldo) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", nome);
        body.put("type", tipo);
        if (saldo != null) body.put("openingBalance", saldo);
        var r = call(HttpMethod.POST, "/api/v1/accounts", body, p);
        assertThat(r.getStatusCode()).as("criar %s: %s", nome, r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private List<Map> listar(Pessoa p, String query) {
        return getList("/api/v1/accounts" + query, p);
    }

    private void lancar(Pessoa p, Object conta, Object contraparte, TransactionType tipo, String valor, boolean excluida) {
        jdbc.update("insert into transactions (user_id, account_id, counterpart_account_id, type, amount, occurred_at, "
                        + "deleted_at) values (?, ?::uuid, ?::uuid, ?, ?::numeric, now(), "
                        + (excluida ? "now()" : "null") + ")",
                p.id(), conta.toString(), contraparte == null ? null : contraparte.toString(), tipo.name(), valor);
    }

    // ---------------------------------------------------------------- acesso

    @Test
    void semTokenEhNaoAutorizado() {
        assertThat(call(HttpMethod.GET, "/api/v1/accounts", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/api/v1/accounts", Map.of("name", "X", "type", "CASH"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void contasDeServicoEDispositivoNaoAcessamDadosFinanceiros() {
        for (Role role : new Role[] {Role.SERVICE, Role.DEVICE}) {
            Pessoa p = pessoa(role);
            assertThat(call(HttpMethod.GET, "/api/v1/accounts", null, p).getStatusCode())
                    .as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(listar(pessoa(Role.ADMIN), "")).isEmpty();   // ADMIN é uma pessoa como as outras: acessa as próprias contas
    }

    // ---------------------------------------------------------------- criação

    @Test
    void criaUmaContaDeCadaTipoComLiquidezESaldoInicial() {
        Pessoa ana = pessoa();
        Map<String, String> esperado = Map.of(
                "CHECKING_ACCOUNT", "AVAILABLE", "SAVINGS_ACCOUNT", "AVAILABLE", "CASH", "AVAILABLE",
                "CREDIT_CARD", "LIABILITY", "VA", "RESTRICTED", "VR", "RESTRICTED",
                "INVESTMENT_ACCOUNT", "INVESTED", "OTHER", "OTHER");
        for (var e : esperado.entrySet()) {
            Map c = criar(ana, "Conta " + e.getKey(), e.getKey(), "100.5");
            assertThat(c).containsEntry("type", e.getKey()).containsEntry("liquidity", e.getValue())
                    .containsEntry("currency", "BRL").containsEntry("archived", false);
            assertThat(c.get("openingBalance")).isEqualTo("100.50");   // string decimal, nunca float
            assertThat(c.get("balance")).isEqualTo("100.50");
            assertThat(c.get("id")).isNotNull();
        }
        assertThat(listar(ana, "")).hasSize(8);
    }

    @Test
    void respostaDeCriacaoTemLocationESaldoZeroPorPadrao() {
        Pessoa ana = pessoa();
        var r = call(HttpMethod.POST, "/api/v1/accounts", Map.of("name", " Carteira ", "type", "CASH"), ana);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getHeaders().getLocation().getPath()).isEqualTo("/api/v1/accounts/" + r.getBody().get("id"));
        assertThat(r.getBody()).containsEntry("name", "Carteira").containsEntry("balance", "0.00");
    }

    @Test
    void saldoInicialNegativoEPermitidoParaDivida() {
        Pessoa ana = pessoa();
        assertThat(criar(ana, "Cartão", "CREDIT_CARD", "-1234.56")).containsEntry("balance", "-1234.56");
    }

    @Test
    void validacoesDeEntrada() {
        Pessoa ana = pessoa();
        record Caso(String descricao, Map<String, Object> corpo, HttpStatus esperado) {}
        List<Caso> casos = new ArrayList<>();
        casos.add(new Caso("nome em branco", Map.of("name", "  ", "type", "CASH"), HttpStatus.BAD_REQUEST));
        casos.add(new Caso("sem nome", Map.of("type", "CASH"), HttpStatus.BAD_REQUEST));
        casos.add(new Caso("nome com 81 caracteres", Map.of("name", "x".repeat(81), "type", "CASH"), HttpStatus.BAD_REQUEST));
        casos.add(new Caso("sem tipo", Map.of("name", "A"), HttpStatus.BAD_REQUEST));
        casos.add(new Caso("tipo inexistente", Map.of("name", "A", "type", "POUPANCA_MAGICA"), HttpStatus.BAD_REQUEST));
        casos.add(new Caso("saldo com 3 casas", Map.of("name", "A", "type", "CASH", "openingBalance", "10.001"), HttpStatus.UNPROCESSABLE_ENTITY));
        casos.add(new Caso("saldo acima do limite", Map.of("name", "A", "type", "CASH", "openingBalance", "1000000000000"), HttpStatus.UNPROCESSABLE_ENTITY));
        casos.add(new Caso("saldo não numérico", Map.of("name", "A", "type", "CASH", "openingBalance", "muito"), HttpStatus.BAD_REQUEST));
        for (Caso c : casos) {
            assertThat(call(HttpMethod.POST, "/api/v1/accounts", c.corpo(), ana).getStatusCode())
                    .as(c.descricao()).isEqualTo(c.esperado());
        }
        assertThat(listar(ana, "")).isEmpty();   // nada foi gravado
    }

    @Test
    void saldoComZerosAExtraEAceitoENormalizado() {
        Pessoa ana = pessoa();
        assertThat(criar(ana, "A", "CASH", "10.500")).containsEntry("openingBalance", "10.50");
        assertThat(criar(ana, "B", "CASH", 25)).containsEntry("openingBalance", "25.00");   // número JSON inteiro
    }

    @Test
    void nomeDuplicadoEntreAtivasDa409SemDiferenciarCaixa() {
        Pessoa ana = pessoa();
        criar(ana, "Nubank", "CHECKING_ACCOUNT", null);

        var r = call(HttpMethod.POST, "/api/v1/accounts", Map.of("name", "NUBANK", "type", "SAVINGS_ACCOUNT"), ana);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(listar(ana, "")).hasSize(1);
    }

    @Test
    void outroUsuarioPodeUsarOMesmoNome() {
        criar(pessoa(), "Carteira", "CASH", null);
        criar(pessoa(), "Carteira", "CASH", null);
    }

    // ---------------------------------------------------------------- saldo derivado

    @Test
    void saldoBateComOCalculoManualParaOsCincoTiposDeTransacao() {
        Pessoa ana = pessoa();
        Object corrente = criar(ana, "Corrente", "CHECKING_ACCOUNT", "1000.00").get("id");
        Object invest = criar(ana, "Corretora", "INVESTMENT_ACCOUNT", null).get("id");
        Object cartao = criar(ana, "Cartão", "CREDIT_CARD", "-200.00").get("id");

        lancar(ana, corrente, null, TransactionType.INCOME, "500.00", false);       // +500
        lancar(ana, corrente, null, TransactionType.EXPENSE, "120.50", false);      // -120,50
        lancar(ana, corrente, cartao, TransactionType.TRANSFER, "300.00", false);   // corrente -300 | cartão +300
        lancar(ana, corrente, invest, TransactionType.INVESTMENT, "200.00", false); // corrente -200 | corretora +200
        lancar(ana, corrente, null, TransactionType.REFUND, "20.50", false);        // +20,50
        lancar(ana, corrente, null, TransactionType.EXPENSE, "999.00", true);       // excluída: não conta

        Map<String, String> saldos = new HashMap<>();
        for (Map c : listar(ana, "")) saldos.put((String) c.get("name"), (String) c.get("balance"));
        assertThat(saldos).containsEntry("Corrente", "900.00")    // 1000 + 500 - 120,50 - 300 - 200 + 20,50
                .containsEntry("Corretora", "200.00")
                .containsEntry("Cartão", "100.00");               // -200 + 300

        var detalhe = call(HttpMethod.GET, "/api/v1/accounts/" + corrente, null, ana);
        assertThat(detalhe.getBody()).containsEntry("balance", "900.00").containsEntry("openingBalance", "1000.00");
    }

    // ---------------------------------------------------------------- listagem e arquivamento

    @Test
    void listaOrdenadaPorNomeEsoComAsDoProprioUsuario() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        criar(ana, "Zeta", "CASH", null);
        criar(ana, "Alfa", "CASH", null);
        criar(bia, "Da Bia", "CASH", null);

        assertThat(listar(ana, "")).extracting(c -> c.get("name")).containsExactly("Alfa", "Zeta");
        assertThat(listar(bia, "")).extracting(c -> c.get("name")).containsExactly("Da Bia");
    }

    @Test
    void arquivarEsconderDaListaPadraoELiberarONome() {
        Pessoa ana = pessoa();
        Object id = criar(ana, "Itaú", "CHECKING_ACCOUNT", null).get("id");

        var arq = call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("archived", true), ana);
        assertThat(arq.getBody()).containsEntry("archived", true);
        assertThat(listar(ana, "")).isEmpty();
        assertThat(listar(ana, "?includeArchived=true")).hasSize(1);

        criar(ana, "Itaú", "CHECKING_ACCOUNT", null);   // nome liberado pelo arquivamento

        // reativar a antiga agora conflita com a nova ativa
        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("archived", false), ana).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------------- edição

    @Test
    void renomeiaEDevolveConflitoSeOutraAtivaJaTemONome() {
        Pessoa ana = pessoa();
        Object id = criar(ana, "Antigo", "CASH", null).get("id");
        criar(ana, "Ocupado", "CASH", null);

        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("name", "Novo"), ana).getBody())
                .containsEntry("name", "Novo");
        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("name", "ocupado"), ana).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("name", "   "), ana).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tipoNaoPodeSerAlteradoMasRepetirOMesmoTipoEhInofensivo() {
        Pessoa ana = pessoa();
        Object id = criar(ana, "Conta", "CASH", null).get("id");

        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("type", "VA"), ana).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("type", "CASH"), ana).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void saldoInicialSoMudaEnquantoNaoHaTransacoes() {
        Pessoa ana = pessoa();
        Object id = criar(ana, "Conta", "CHECKING_ACCOUNT", "10.00").get("id");

        var ok = call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("openingBalance", "50.25"), ana);
        assertThat(ok.getBody()).containsEntry("openingBalance", "50.25").containsEntry("balance", "50.25");

        lancar(ana, id, null, TransactionType.INCOME, "5.00", false);
        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("openingBalance", "99.00"), ana).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // repetir o valor atual não é mudança
        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("openingBalance", "50.25"), ana).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("openingBalance", "1.234"), ana).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---------------------------------------------------------------- isolamento (usuário B nunca toca a conta do A)

    @Test
    void contaDeOutroUsuarioEhInvisivelNaLeituraEdicaoEArquivamento() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        Object idDaAna = criar(ana, "Reserva da Ana", "SAVINGS_ACCOUNT", "300.00").get("id");
        String url = "/api/v1/accounts/" + idDaAna;

        assertThat(call(HttpMethod.GET, url, null, bia).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PATCH, url, Map.of("name", "Invadida"), bia).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PATCH, url, Map.of("archived", true), bia).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PATCH, url, Map.of("openingBalance", "0"), bia).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/accounts/" + UUID.randomUUID(), null, bia).getStatusCode())
                .as("inexistente responde igual a 'de outro usuário'").isEqualTo(HttpStatus.NOT_FOUND);

        // a conta da Ana segue intacta
        assertThat(call(HttpMethod.GET, url, null, ana).getBody())
                .containsEntry("name", "Reserva da Ana").containsEntry("balance", "300.00").containsEntry("archived", false);
    }

    @Test
    void idMalformadoDa400() {
        assertThat(call(HttpMethod.GET, "/api/v1/accounts/nao-eh-uuid", null, pessoa()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- auditoria e contrato

    @Test
    void auditoriaRegistraOEventoMasNuncaONomeNemOsValores() {
        Pessoa ana = pessoa();
        Object id = criar(ana, "Conta Secretíssima", "CHECKING_ACCOUNT", "7777.77").get("id");
        call(HttpMethod.PATCH, "/api/v1/accounts/" + id, Map.of("name", "Outro Segredo", "archived", true), ana);

        List<Map<String, Object>> linhas = jdbc.queryForList(
                "select action, resource, detail::text as detail from audit_logs "
                        + "where actor_id = ? and resource like 'account:%' order by occurred_at, id",
                ana.id());
        assertThat(linhas).extracting(l -> l.get("action"))
                .contains("ACCOUNT_CREATED", "ACCOUNT_UPDATED", "ACCOUNT_ARCHIVED");
        for (var l : linhas) {
            assertThat(l.get("resource")).isEqualTo("account:" + id);
            assertThat((String) l.get("detail")).doesNotContain("Secret", "7777", "Segredo");
        }
    }

    @Test
    void openApiDocumentaOsEndpointsDeContas() {
        var r = rest.getForEntity("/api/v1/openapi", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map paths = (Map) r.getBody().get("paths");
        assertThat(paths).containsKeys("/api/v1/accounts", "/api/v1/accounts/{id}");
    }
}
