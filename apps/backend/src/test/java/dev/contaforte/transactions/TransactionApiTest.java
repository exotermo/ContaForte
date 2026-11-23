package dev.contaforte.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import dev.contaforte.users.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SuppressWarnings({"rawtypes", "unchecked"})
class TransactionApiTest extends ApiTestSupport {

    @Autowired RecordedEvents recorded;
    @Autowired MeterRegistry metrics;

    // ---------------------------------------------------------------- apoio

    private Map pagina(Pessoa p, String query) {
        var r = call(HttpMethod.GET, "/api/v1/transactions" + query, null, p);
        assertThat(r.getStatusCode()).as(query + " -> " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private List<String> descricoes(Pessoa p, String query) {
        return ((List<Map>) pagina(p, query).get("items")).stream().map(i -> (String) i.get("description")).toList();
    }

    // ---------------------------------------------------------------- acesso

    @Test
    void semTokenEhNaoAutorizadoEServicoEDispositivoRecebem403() {
        assertThat(status(HttpMethod.GET, "/api/v1/transactions", null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.POST, "/api/v1/transactions", Map.of(), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (Role role : new Role[] {Role.SERVICE, Role.DEVICE}) {
            assertThat(status(HttpMethod.GET, "/api/v1/transactions", null, pessoa(role))).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------- criação e efeito no saldo

    @Test
    void saldoBateComOCalculoManualParaOsCincoTipos() {
        Pessoa ana = pessoa();
        String corrente = conta(ana, "Corrente", "CHECKING_ACCOUNT", "1000.00");
        String corretora = conta(ana, "Corretora", "INVESTMENT_ACCOUNT", null);
        String cartao = conta(ana, "Cartão", "CREDIT_CARD", "-200.00");

        criada(ana, corpo("INCOME", corrente, "500.00"));                                   // +500
        criada(ana, corpo("EXPENSE", corrente, "120.50"));                                  // -120,50
        Map transf = corpo("TRANSFER", corrente, "300.00"); transf.put("counterpartAccountId", cartao);
        criada(ana, transf);                                                                // corrente -300 | cartão +300
        Map invest = corpo("INVESTMENT", corrente, "200.00"); invest.put("counterpartAccountId", corretora);
        criada(ana, invest);                                                                // corrente -200 | corretora +200
        criada(ana, corpo("REFUND", corrente, "20.50"));                                    // +20,50

        assertThat(saldo(ana, corrente)).isEqualTo("900.00");   // 1000 + 500 - 120,50 - 300 - 200 + 20,50
        assertThat(saldo(ana, corretora)).isEqualTo("200.00");
        assertThat(saldo(ana, cartao)).isEqualTo("100.00");     // -200 + 300
    }

    @Test
    void centavosSomamExatamenteSemErroDePontoFlutuante() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        for (int i = 0; i < 10; i++) criada(ana, corpo("INCOME", c, "0.10"));
        criada(ana, corpo("EXPENSE", c, "0.30"));

        assertThat(saldo(ana, c)).isEqualTo("0.70");   // com double daria 0.7000000000000001
    }

    @Test
    void respostaDeCriacaoTemFormatoEDefaultsCorretos() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        Map<String, Object> b = corpo("EXPENSE", c, 12.5);   // número JSON
        b.put("description", "  Almoço  ");
        b.put("merchant", "   ");
        Instant antes = Instant.now().minusSeconds(5);

        var r = lancar(ana, b);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getHeaders().getLocation().getPath()).isEqualTo("/api/v1/transactions/" + r.getBody().get("id"));
        assertThat(r.getBody()).containsEntry("amount", "12.50").containsEntry("currency", "BRL")
                .containsEntry("source", "MANUAL").containsEntry("description", "Almoço").containsEntry("accountId", c);
        assertThat(r.getBody().get("merchant")).isNull();           // em branco vira nulo
        assertThat(r.getBody().get("counterpartAccountId")).isNull();
        assertThat(Instant.parse((String) r.getBody().get("occurredAt"))).isAfter(antes);   // sem data = agora
    }

    @Test
    void clienteNaoEscolheAOrigem() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        Map b = corpo("EXPENSE", c, "1.00");
        b.put("source", "PIX");

        assertThat(criada(ana, b)).containsEntry("source", "MANUAL");
    }

    // ---------------------------------------------------------------- valores

    @Test
    void bordasDeValor() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        Map<Object, HttpStatus> casos = new java.util.LinkedHashMap<>();
        casos.put("0", HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put("0.00", HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put("-1.00", HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put("0.001", HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put("0.005", HttpStatus.UNPROCESSABLE_ENTITY);   // o banco arredondaria para 0,01 em silêncio
        casos.put("10.999", HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put("1000000000000", HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put("muito", HttpStatus.BAD_REQUEST);
        casos.put("0.01", HttpStatus.CREATED);
        casos.put("10.500", HttpStatus.CREATED);
        casos.put("999999999999.99", HttpStatus.CREATED);
        for (var e : casos.entrySet()) {
            assertThat(status(HttpMethod.POST, "/api/v1/transactions", corpo("INCOME", c, e.getKey()), ana))
                    .as("valor %s", e.getKey()).isEqualTo(e.getValue());
        }
        Map semValor = corpo("INCOME", c, null);
        assertThat(status(HttpMethod.POST, "/api/v1/transactions", semValor, ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- datas

    @Test
    void validacaoDeData() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        Map<String, HttpStatus> casos = new java.util.LinkedHashMap<>();
        casos.put(Instant.now().plus(2, ChronoUnit.DAYS).toString(), HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put("1999-12-31T23:59:59Z", HttpStatus.UNPROCESSABLE_ENTITY);
        casos.put(Instant.now().plus(12, ChronoUnit.HOURS).toString(), HttpStatus.CREATED);   // dentro da tolerância de 1 dia
        casos.put("2026-01-15T12:00:00Z", HttpStatus.CREATED);
        for (var e : casos.entrySet()) {
            Map b = corpo("EXPENSE", c, "1.00");
            b.put("occurredAt", e.getKey());
            assertThat(status(HttpMethod.POST, "/api/v1/transactions", b, ana)).as("data %s", e.getKey()).isEqualTo(e.getValue());
        }
        Map ruim = corpo("EXPENSE", c, "1.00");
        ruim.put("occurredAt", "ontem");
        assertThat(status(HttpMethod.POST, "/api/v1/transactions", ruim, ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- regras de tipo e contas

    @Test
    void regrasDeContraparte() {
        Pessoa ana = pessoa();
        String corrente = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String poupanca = conta(ana, "Poupança", "SAVINGS_ACCOUNT", null);
        String corretora = conta(ana, "Corretora", "INVESTMENT_ACCOUNT", null);

        Map receitaComDestino = corpo("INCOME", corrente, "10.00"); receitaComDestino.put("counterpartAccountId", poupanca);
        assertThat(lancar(ana, receitaComDestino).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(lancar(ana, corpo("TRANSFER", corrente, "10.00")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(lancar(ana, corpo("INVESTMENT", corrente, "10.00")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map paraSiMesma = corpo("TRANSFER", corrente, "10.00"); paraSiMesma.put("counterpartAccountId", corrente);
        assertThat(lancar(ana, paraSiMesma).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map investirNaPoupanca = corpo("INVESTMENT", corrente, "10.00"); investirNaPoupanca.put("counterpartAccountId", poupanca);
        assertThat(lancar(ana, investirNaPoupanca).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map transferirParaCorretora = corpo("TRANSFER", corrente, "10.00"); transferirParaCorretora.put("counterpartAccountId", corretora);
        assertThat(lancar(ana, transferirParaCorretora).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map investir = corpo("INVESTMENT", corrente, "10.00"); investir.put("counterpartAccountId", corretora);
        assertThat(lancar(ana, investir).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(status(HttpMethod.POST, "/api/v1/transactions", corpo("DOACAO", corrente, "1.00"), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void contaArquivadaNaoRecebeTransacaoNemComoOrigemNemComoDestino() {
        Pessoa ana = pessoa();
        String ativa = conta(ana, "Ativa", "CHECKING_ACCOUNT", null);
        String velha = conta(ana, "Velha", "SAVINGS_ACCOUNT", null);
        call(HttpMethod.PATCH, "/api/v1/accounts/" + velha, Map.of("archived", true), ana);

        assertThat(lancar(ana, corpo("EXPENSE", velha, "1.00")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map transf = corpo("TRANSFER", ativa, "1.00"); transf.put("counterpartAccountId", velha);
        assertThat(lancar(ana, transf).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void categoriaEContextoPadraoEPropriosSaoAceitos() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        String propria = (String) call(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Minha"), ana).getBody().get("id");
        Map b = corpo("EXPENSE", c, "30.00");
        b.put("categoryId", categoriaPadrao(ana, "FOOD_RESTAURANT"));
        b.put("contextId", contextoPadrao(ana, "RELATIONSHIP"));
        Map t = criada(ana, b);
        assertThat(t.get("categoryId")).isEqualTo(b.get("categoryId"));
        assertThat(t.get("contextId")).isEqualTo(b.get("contextId"));

        Map b2 = corpo("EXPENSE", c, "1.00"); b2.put("categoryId", propria);
        assertThat(lancar(ana, b2).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---------------------------------------------------------------- isolamento

    @Test
    void referenciasEAcessosDeOutroUsuarioDao404() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String contaAna = conta(ana, "Da Ana", "CHECKING_ACCOUNT", null);
        String contaBia = conta(bia, "Da Bia", "CHECKING_ACCOUNT", null);
        String catAna = (String) call(HttpMethod.POST, "/api/v1/categories", Map.of("name", "Cat da Ana"), ana).getBody().get("id");
        String ctxAna = (String) call(HttpMethod.POST, "/api/v1/contexts", Map.of("name", "Ctx da Ana"), ana).getBody().get("id");
        Map daAna = criada(ana, corpo("EXPENSE", contaAna, "50.00"));
        String url = "/api/v1/transactions/" + daAna.get("id");

        // Bia lançando na conta, no destino, na categoria ou no contexto da Ana
        assertThat(lancar(bia, corpo("EXPENSE", contaAna, "1.00")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map transf = corpo("TRANSFER", contaBia, "1.00"); transf.put("counterpartAccountId", contaAna);
        assertThat(lancar(bia, transf).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map comCat = corpo("EXPENSE", contaBia, "1.00"); comCat.put("categoryId", catAna);
        assertThat(lancar(bia, comCat).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map comCtx = corpo("EXPENSE", contaBia, "1.00"); comCtx.put("contextId", ctxAna);
        assertThat(lancar(bia, comCtx).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Bia lendo, editando e excluindo a transação da Ana
        assertThat(status(HttpMethod.GET, url, null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.PATCH, url, Map.of("description", "invadida"), bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.DELETE, url, null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, "/api/v1/transactions/" + UUID.randomUUID(), null, bia))
                .as("inexistente responde igual a 'de outro usuário'").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List) pagina(bia, "").get("items")).isEmpty();
        assertThat(pagina(bia, "?accountId=" + contaAna).get("totalElements")).isEqualTo(0);

        // intacta para a Ana
        assertThat(call(HttpMethod.GET, url, null, ana).getBody()).containsEntry("amount", "50.00").doesNotContainEntry("description", "invadida");
        assertThat(saldo(ana, contaAna)).isEqualTo("-50.00");
    }

    // ---------------------------------------------------------------- listagem

    @Test
    void listaOrdenadaDoMaisRecentePraMaisAntigaEPagina() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        for (int dia = 1; dia <= 5; dia++) {
            Map b = corpo("EXPENSE", c, "1.00");
            b.put("occurredAt", "2026-02-0" + dia + "T15:00:00Z");
            b.put("description", "dia " + dia);
            criada(ana, b);
        }

        assertThat(descricoes(ana, "")).containsExactly("dia 5", "dia 4", "dia 3", "dia 2", "dia 1");
        Map p0 = pagina(ana, "?size=2&page=0");
        Map p2 = pagina(ana, "?size=2&page=2");
        assertThat(p0).containsEntry("totalElements", 5).containsEntry("totalPages", 3).containsEntry("size", 2).containsEntry("page", 0);
        assertThat(descricoes(ana, "?size=2&page=0")).containsExactly("dia 5", "dia 4");
        assertThat(descricoes(ana, "?size=2&page=2")).containsExactly("dia 1");
        assertThat((List) p2.get("items")).hasSize(1);
        assertThat((List) pagina(ana, "?size=2&page=9").get("items")).isEmpty();
    }

    @Test
    void paginacaoInvalidaDa400() {
        Pessoa ana = pessoa();
        for (String q : new String[] {"?size=0", "?size=101", "?page=-1", "?size=abc"}) {
            assertThat(status(HttpMethod.GET, "/api/v1/transactions" + q, null, ana)).as(q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(pagina(ana, "?size=100").get("size")).isEqualTo(100);
    }

    @Test
    void filtroPorPeriodoUsaOFusoDeSaoPauloComDiaFinalInclusivo() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        // 23:30 de 30/03 em São Paulo (UTC-3) já é 31/03 em UTC
        Map tarde = corpo("EXPENSE", c, "1.00"); tarde.put("occurredAt", "2026-03-31T02:30:00Z"); tarde.put("description", "30/03 23h30");
        // 00:00 de 31/03 em São Paulo
        Map meiaNoite = corpo("EXPENSE", c, "1.00"); meiaNoite.put("occurredAt", "2026-03-31T03:00:00Z"); meiaNoite.put("description", "31/03 00h00");
        criada(ana, tarde);
        criada(ana, meiaNoite);

        assertThat(descricoes(ana, "?to=2026-03-30")).containsExactly("30/03 23h30");
        assertThat(descricoes(ana, "?from=2026-03-31")).containsExactly("31/03 00h00");
        assertThat(descricoes(ana, "?from=2026-03-30&to=2026-03-31")).containsExactly("31/03 00h00", "30/03 23h30");
        assertThat(status(HttpMethod.GET, "/api/v1/transactions?from=2026-04-02&to=2026-04-01", null, ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.GET, "/api/v1/transactions?from=31-03-2026", null, ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void filtrosPorContaTipoCategoriaContextoETexto() {
        Pessoa ana = pessoa();
        String corrente = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String poupanca = conta(ana, "Poupança", "SAVINGS_ACCOUNT", null);
        String alimentacao = categoriaPadrao(ana, "FOOD");
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        String transporte = categoriaPadrao(ana, "TRANSPORT");

        Map almoco = corpo("EXPENSE", corrente, "30.00"); almoco.put("description", "Almoço"); almoco.put("merchant", "Cantina Zé");
        almoco.put("categoryId", restaurante); almoco.put("contextId", contextoPadrao(ana, "RELATIONSHIP")); criada(ana, almoco);
        Map mercado = corpo("EXPENSE", corrente, "200.00"); mercado.put("description", "Mercado do mês");
        mercado.put("categoryId", alimentacao); criada(ana, mercado);
        Map taxi = corpo("EXPENSE", poupanca, "15.00"); taxi.put("description", "Táxi"); taxi.put("categoryId", transporte); criada(ana, taxi);
        Map transf = corpo("TRANSFER", corrente, "100.00"); transf.put("counterpartAccountId", poupanca); transf.put("description", "Reserva"); criada(ana, transf);

        // conta: casa a origem E o destino
        assertThat(descricoes(ana, "?accountId=" + poupanca)).containsExactlyInAnyOrder("Táxi", "Reserva");
        assertThat(descricoes(ana, "?accountId=" + corrente)).containsExactlyInAnyOrder("Almoço", "Mercado do mês", "Reserva");
        assertThat(descricoes(ana, "?type=TRANSFER")).containsExactly("Reserva");
        // categoria-pai inclui as filhas; filha só ela mesma
        assertThat(descricoes(ana, "?categoryId=" + alimentacao)).containsExactlyInAnyOrder("Almoço", "Mercado do mês");
        assertThat(descricoes(ana, "?categoryId=" + restaurante)).containsExactly("Almoço");
        assertThat(descricoes(ana, "?contextId=" + contextoPadrao(ana, "RELATIONSHIP"))).containsExactly("Almoço");
        // texto: descrição ou estabelecimento, sem diferenciar caixa
        assertThat(descricoes(ana, "?q=MERCADO")).containsExactly("Mercado do mês");
        assertThat(descricoes(ana, "?q=cantina")).containsExactly("Almoço");
        // combinação de filtros (E)
        assertThat(descricoes(ana, "?accountId=" + corrente + "&categoryId=" + alimentacao + "&q=almo")).containsExactly("Almoço");
        assertThat(descricoes(ana, "?type=INCOME")).isEmpty();
    }

    @Test
    void buscaPorTextoTrataCuringasComoTextoLiteral() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        for (String d : new String[] {"100% real", "1000 reais", "a_b", "axb"}) {
            Map b = corpo("EXPENSE", c, "1.00"); b.put("description", d); criada(ana, b);
        }

        // o TestRestTemplate codifica o '%' ao montar a URL
        assertThat(descricoes(ana, "?q=100%")).containsExactly("100% real");
        assertThat(descricoes(ana, "?q=a_b")).containsExactly("a_b");            // '_' não casa 'axb'
        assertThat(descricoes(ana, "?q=%")).containsExactly("100% real");        // '%' sozinho não casa tudo
    }

    // ---------------------------------------------------------------- edição

    @Test
    void editaValorDataEDescricaoEOSaldoAcompanha() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", "100.00");
        Map t = criada(ana, corpo("EXPENSE", c, "30.00"));
        String url = "/api/v1/transactions/" + t.get("id");
        assertThat(saldo(ana, c)).isEqualTo("70.00");

        Map patch = new HashMap<>();
        patch.put("amount", "45.50");
        patch.put("occurredAt", "2026-01-10T12:00:00Z");
        patch.put("description", "Corrigido");
        var r = call(HttpMethod.PATCH, url, patch, ana);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("amount", "45.50").containsEntry("description", "Corrigido")
                .containsEntry("occurredAt", "2026-01-10T12:00:00Z");
        assertThat(saldo(ana, c)).isEqualTo("54.50");
    }

    @Test
    void camposAusentesNaoMudamENullExplicitoLimpa() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        Map b = corpo("EXPENSE", c, "10.00");
        b.put("description", "Original"); b.put("merchant", "Loja");
        b.put("categoryId", categoriaPadrao(ana, "FOOD")); b.put("contextId", contextoPadrao(ana, "PET"));
        Map t = criada(ana, b);
        String url = "/api/v1/transactions/" + t.get("id");

        // só mexe na descrição: o resto fica
        Map so = call(HttpMethod.PATCH, url, Map.of("description", "Novo"), ana).getBody();
        assertThat(so).containsEntry("description", "Novo").containsEntry("merchant", "Loja")
                .containsEntry("categoryId", b.get("categoryId")).containsEntry("contextId", b.get("contextId"));

        // null explícito limpa categoria e contexto, e a descrição em branco também
        Map limpar = new HashMap<>();
        limpar.put("categoryId", null); limpar.put("contextId", null); limpar.put("description", "   ");
        Map limpo = call(HttpMethod.PATCH, url, limpar, ana).getBody();
        assertThat(limpo.get("categoryId")).isNull();
        assertThat(limpo.get("contextId")).isNull();
        assertThat(limpo.get("description")).isNull();
        assertThat(limpo).containsEntry("merchant", "Loja").containsEntry("amount", "10.00");

        // corpo vazio não muda nada
        assertThat(call(HttpMethod.PATCH, url, Map.of(), ana).getBody()).containsEntry("merchant", "Loja");
    }

    @Test
    void tipoEContasNaoMudamERegrasDeValorValemNaEdicao() {
        Pessoa ana = pessoa();
        String c1 = conta(ana, "Conta 1", "CHECKING_ACCOUNT", null);
        String c2 = conta(ana, "Conta 2", "CHECKING_ACCOUNT", null);
        Map t = criada(ana, corpo("EXPENSE", c1, "10.00"));
        String url = "/api/v1/transactions/" + t.get("id");

        assertThat(status(HttpMethod.PATCH, url, Map.of("type", "INCOME"), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.PATCH, url, Map.of("accountId", c2), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.PATCH, url, Map.of("counterpartAccountId", c2), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.PATCH, url, Map.of("type", "EXPENSE", "accountId", c1), ana)).isEqualTo(HttpStatus.OK);   // igual ao atual
        assertThat(status(HttpMethod.PATCH, url, Map.of("amount", "0"), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.PATCH, url, Map.of("amount", "1.005"), ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.PATCH, url, Map.of("occurredAt", Instant.now().plus(3, ChronoUnit.DAYS).toString()), ana))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.PATCH, url, Map.of("description", "x".repeat(201)), ana)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.PATCH, url, Map.of("categoryId", UUID.randomUUID().toString()), ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, url, null, ana).getBody()).containsEntry("amount", "10.00");   // nada mudou
    }

    // ---------------------------------------------------------------- exclusão lógica

    @Test
    void exclusaoLogicaSomeDeListaDetalheESaldoMasALinhaFicaNoBanco() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", "100.00");
        Map t = criada(ana, corpo("EXPENSE", c, "40.00"));
        String url = "/api/v1/transactions/" + t.get("id");
        assertThat(saldo(ana, c)).isEqualTo("60.00");

        assertThat(status(HttpMethod.DELETE, url, null, ana)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(status(HttpMethod.GET, url, null, ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List) pagina(ana, "").get("items")).isEmpty();
        assertThat(saldo(ana, c)).isEqualTo("100.00");
        assertThat(status(HttpMethod.DELETE, url, null, ana)).as("segunda exclusão").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.PATCH, url, Map.of("description", "zumbi"), ana)).isEqualTo(HttpStatus.NOT_FOUND);
        Integer naoExcluidas = jdbc.queryForObject("select count(*) from transactions where id = ?::uuid and deleted_at is null",
                Integer.class, t.get("id").toString());
        Integer naTabela = jdbc.queryForObject("select count(*) from transactions where id = ?::uuid", Integer.class, t.get("id").toString());
        assertThat(naoExcluidas).isZero();
        assertThat(naTabela).isEqualTo(1);
    }

    // ---------------------------------------------------------------- evento, métrica, auditoria, concorrência

    @Test
    void publicaEventoSemDadosPessoaisEIncrementaAMetricaPorTipo() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        double antes = metrics.counter("contaforte.transactions.recorded", "type", "EXPENSE").count();
        Map b = corpo("EXPENSE", c, "777.77"); b.put("description", "Descrição Reservada");

        Map t = criada(ana, b);

        List<TransactionRecorded> meus = recorded.all().stream().filter(e -> e.userId().equals(ana.id())).toList();
        assertThat(meus).hasSize(1);
        assertThat(meus.get(0).transactionId()).isEqualTo(UUID.fromString((String) t.get("id")));
        assertThat(meus.get(0).accountId()).isEqualTo(UUID.fromString(c));
        assertThat(meus.get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(meus.get(0).toString()).doesNotContain("777", "Reservada");
        assertThat(metrics.counter("contaforte.transactions.recorded", "type", "EXPENSE").count()).isEqualTo(antes + 1);
        // nenhuma tag de métrica do servidor ou da regra de negócio carrega descrição ou valor
        // (o http.client.requests é do próprio TestRestTemplate e leva a URL que o teste chama)
        assertThat(metrics.getMeters().stream()
                .filter(m -> !m.getId().getName().startsWith("http.client"))
                .flatMap(m -> m.getId().getTags().stream()).map(tag -> tag.getValue()))
                .noneMatch(v -> v.contains("Reservada") || v.contains("777.77"));
    }

    @Test
    void auditoriaRegistraOsEventosSemValorNemDescricao() {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", null);
        Map b = corpo("EXPENSE", c, "888.88"); b.put("description", "Segredo Absoluto");
        Map t = criada(ana, b);
        String url = "/api/v1/transactions/" + t.get("id");
        call(HttpMethod.PATCH, url, Map.of("amount", "999.99", "description", "Outro Segredo"), ana);
        call(HttpMethod.DELETE, url, null, ana);

        List<Map<String, Object>> linhas = jdbc.queryForList(
                "select action, resource, detail::text as detail from audit_logs where actor_id = ? and resource like 'transaction:%'", ana.id());
        assertThat(linhas).extracting(l -> l.get("action"))
                .containsExactlyInAnyOrder("TRANSACTION_CREATED", "TRANSACTION_UPDATED", "TRANSACTION_DELETED");
        for (var l : linhas) {
            assertThat((String) l.get("detail")).doesNotContain("888", "999", "Segredo");
            assertThat((String) l.get("resource")).isEqualTo("transaction:" + t.get("id"));
        }
    }

    @Test
    void criacoesSimultaneasMantemOSaldoCerto() throws Exception {
        Pessoa ana = pessoa();
        String c = conta(ana, "Conta", "CHECKING_ACCOUNT", "1000.00");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<HttpStatus>> tarefas = new ArrayList<>();
            for (int i = 0; i < 20; i++) tarefas.add(() -> status(HttpMethod.POST, "/api/v1/transactions", corpo("EXPENSE", c, "10.05"), ana));
            for (Future<HttpStatus> f : pool.invokeAll(tarefas)) assertThat(f.get()).isEqualTo(HttpStatus.CREATED);
        } finally {
            pool.shutdownNow();
        }

        assertThat(saldo(ana, c)).isEqualTo("799.00");   // 1000 - 20 x 10,05
        assertThat(pagina(ana, "").get("totalElements")).isEqualTo(20);
    }

    @Test
    void openApiDocumentaOsEndpoints() {
        Map paths = (Map) rest.getForEntity("/api/v1/openapi", Map.class).getBody().get("paths");
        assertThat(paths).containsKeys("/api/v1/transactions", "/api/v1/transactions/{id}");
    }
}
