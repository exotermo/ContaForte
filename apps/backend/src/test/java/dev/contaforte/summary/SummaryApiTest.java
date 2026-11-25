package dev.contaforte.summary;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import dev.contaforte.users.Role;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SuppressWarnings({"rawtypes", "unchecked"})
class SummaryApiTest extends ApiTestSupport {

    private Map resumo(Pessoa p) {
        var r = call(HttpMethod.GET, "/api/v1/summary", null, p);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private Map mes(Pessoa p, String mes) {
        var r = call(HttpMethod.GET, "/api/v1/summary/monthly" + (mes == null ? "" : "?month=" + mes), null, p);
        assertThat(r.getStatusCode()).as(mes + " -> " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private Map historico(Pessoa p, String query) {
        var r = call(HttpMethod.GET, "/api/v1/summary/history" + query, null, p);
        assertThat(r.getStatusCode()).as(query + " -> " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private Map<String, Object> em(Map<String, Object> corpo, String instante) {
        corpo.put("occurredAt", instante);
        return corpo;
    }

    private Map<String, Object> com(Map<String, Object> corpo, String chave, Object valor) {
        corpo.put(chave, valor);
        return corpo;
    }

    private Map no(List<Map> linhas, String nome) {
        return linhas.stream().filter(l -> nome.equals(l.get("name"))).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- acesso

    @Test
    void semTokenEhNaoAutorizadoEServicoEDispositivoRecebem403() {
        assertThat(status(HttpMethod.GET, "/api/v1/summary", null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.GET, "/api/v1/summary/monthly", null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.GET, "/api/v1/summary/history", null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (Role role : new Role[] {Role.SERVICE, Role.DEVICE}) {
            Pessoa p = pessoa(role);
            assertThat(status(HttpMethod.GET, "/api/v1/summary", null, p)).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(HttpMethod.GET, "/api/v1/summary/monthly", null, p)).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(HttpMethod.GET, "/api/v1/summary/history", null, p)).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------- posição atual

    @Test
    void usuarioSemContasTemTudoZeradoEmFormatoDecimal() {
        Map r = resumo(pessoa());

        for (String campo : new String[] {"availableCash", "restrictedBalance", "investedAssets", "creditCardBalance", "otherBalance", "totalNetWorth"}) {
            assertThat(r.get(campo)).as(campo).isEqualTo("0.00");   // string decimal, nunca número
        }
        assertThat((List) r.get("accounts")).isEmpty();
    }

    @Test
    void exemploDoSddSeparaLiquidezCorretamente() {
        Pessoa ana = pessoa();
        conta(ana, "Banco", "CHECKING_ACCOUNT", "4200.00");
        conta(ana, "Vale alimentação", "VA", "700.00");
        conta(ana, "Vale refeição", "VR", "500.00");
        conta(ana, "Corretora", "INVESTMENT_ACCOUNT", "8400.00");

        Map r = resumo(ana);

        assertThat(r).containsEntry("availableCash", "4200.00")      // VA/VR fora do caixa disponível
                .containsEntry("restrictedBalance", "1200.00")
                .containsEntry("investedAssets", "8400.00")
                .containsEntry("creditCardBalance", "0.00")
                .containsEntry("otherBalance", "0.00")
                .containsEntry("totalNetWorth", "13800.00");
        List<Map> contas = (List<Map>) r.get("accounts");
        assertThat(contas).hasSize(4);
        assertThat(no(contas, "Vale alimentação")).containsEntry("liquidity", "RESTRICTED").containsEntry("balance", "700.00");
    }

    @Test
    void vaEVrNuncaEntramNoCaixaDisponivelPorMaisQueRecebam() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", "1000.00");
        String va = conta(ana, "VA", "VA", null);
        String vr = conta(ana, "VR", "VR", null);

        criada(ana, corpo("INCOME", va, "900.00"));           // recarga do benefício
        criada(ana, corpo("INCOME", vr, "600.00"));
        criada(ana, com(corpo("TRANSFER", banco, "50.00"), "counterpartAccountId", va));   // até transferindo do banco para o VA

        Map r = resumo(ana);
        assertThat(r).containsEntry("availableCash", "950.00")      // só o banco: 1000 - 50
                .containsEntry("restrictedBalance", "1550.00");     // 900 + 600 + 50
        for (Map conta : (List<Map>) r.get("accounts")) {
            if ("VA".equals(conta.get("type")) || "VR".equals(conta.get("type"))) {
                assertThat(conta.get("liquidity")).isEqualTo("RESTRICTED");
            }
        }
    }

    @Test
    void transferenciaEInvestimentoNaoMudamOPatrimonioMasMovemEntreClasses() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", "1000.00");
        String corretora = conta(ana, "Corretora", "INVESTMENT_ACCOUNT", null);
        String poupanca = conta(ana, "Poupança", "SAVINGS_ACCOUNT", null);
        String patrimonioAntes = (String) resumo(ana).get("totalNetWorth");

        criada(ana, com(corpo("INVESTMENT", banco, "400.00"), "counterpartAccountId", corretora));
        criada(ana, com(corpo("TRANSFER", banco, "100.00"), "counterpartAccountId", poupanca));

        Map r = resumo(ana);
        assertThat(r).containsEntry("totalNetWorth", patrimonioAntes)          // conservação: o dinheiro só mudou de lugar
                .containsEntry("investedAssets", "400.00")
                .containsEntry("availableCash", "600.00");                       // banco 500 + poupança 100
        // receita e despesa, ao contrário, mexem no patrimônio
        criada(ana, corpo("EXPENSE", banco, "50.00"));
        assertThat(resumo(ana)).containsEntry("totalNetWorth", "950.00");
    }

    @Test
    void cartaoDeCreditoEPassivoQuePagarAFaturaNaoAlteraOPatrimonio() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", "1000.00");
        String cartao = conta(ana, "Cartão", "CREDIT_CARD", "-300.00");

        Map antes = resumo(ana);
        assertThat(antes).containsEntry("creditCardBalance", "-300.00")
                .containsEntry("availableCash", "1000.00")        // a dívida não reduz o caixa disponível
                .containsEntry("totalNetWorth", "700.00");

        criada(ana, corpo("EXPENSE", cartao, "50.00"));            // compra no cartão
        assertThat(resumo(ana)).containsEntry("creditCardBalance", "-350.00").containsEntry("totalNetWorth", "650.00");

        criada(ana, com(corpo("TRANSFER", banco, "350.00"), "counterpartAccountId", cartao));   // paga a fatura
        Map depois = resumo(ana);
        assertThat(depois).containsEntry("creditCardBalance", "0.00")
                .containsEntry("availableCash", "650.00")
                .containsEntry("totalNetWorth", "650.00");         // quitar a dívida não muda o patrimônio
    }

    @Test
    void tipoOutroEntraSoNoPatrimonio() {
        Pessoa ana = pessoa();
        conta(ana, "Imóvel", "OTHER", "100000.00");

        assertThat(resumo(ana)).containsEntry("otherBalance", "100000.00").containsEntry("totalNetWorth", "100000.00")
                .containsEntry("availableCash", "0.00").containsEntry("investedAssets", "0.00");
    }

    @Test
    void contaArquivadaEExcluidaDeTodosOsTotaisEDaLista() {
        Pessoa ana = pessoa();
        conta(ana, "Ativa", "CHECKING_ACCOUNT", "100.00");
        String velha = conta(ana, "Velha", "SAVINGS_ACCOUNT", "900.00");
        assertThat(resumo(ana)).containsEntry("availableCash", "1000.00");

        call(HttpMethod.PATCH, "/api/v1/accounts/" + velha, Map.of("archived", true), ana);

        Map r = resumo(ana);
        assertThat(r).containsEntry("availableCash", "100.00").containsEntry("totalNetWorth", "100.00");
        assertThat((List<Map>) r.get("accounts")).extracting(c -> c.get("name")).containsExactly("Ativa");
    }

    @Test
    void transacaoExcluidaNaoCountaNoResumo() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", "100.00");
        Map t = criada(ana, corpo("EXPENSE", banco, "30.00"));
        assertThat(resumo(ana)).containsEntry("availableCash", "70.00");

        call(HttpMethod.DELETE, "/api/v1/transactions/" + t.get("id"), null, ana);

        assertThat(resumo(ana)).containsEntry("availableCash", "100.00");
    }

    @Test
    void resumoNuncaMisturaDadosDeOutroUsuario() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        conta(ana, "Da Ana", "CHECKING_ACCOUNT", "5000.00");
        String daBia = conta(bia, "Da Bia", "CHECKING_ACCOUNT", "10.00");
        criada(bia, corpo("EXPENSE", daBia, "1.00"));

        assertThat(resumo(bia)).containsEntry("availableCash", "9.00").containsEntry("totalNetWorth", "9.00");
        assertThat(resumo(ana)).containsEntry("availableCash", "5000.00");
        assertThat((List) mes(ana, null).get("byCategory")).isEmpty();
    }

    @Test
    void totaisSaoExatosComMuitosCentavos() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        for (int i = 0; i < 30; i++) criada(ana, corpo("INCOME", banco, "0.10"));

        assertThat(resumo(ana)).containsEntry("availableCash", "3.00");   // com double: 2.9999999999999996
    }

    // ---------------------------------------------------------------- mês

    @Test
    void agregaReceitaGastoEstornoEInvestidoEIgnoraTransferencia() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        String corretora = conta(ana, "Corretora", "INVESTMENT_ACCOUNT", null);
        String cartao = conta(ana, "Cartão", "CREDIT_CARD", null);
        String dia = "2026-05-10T15:00:00Z";

        criada(ana, em(corpo("INCOME", banco, "5000.00"), dia));
        criada(ana, em(corpo("EXPENSE", banco, "100.00"), dia));
        criada(ana, em(corpo("EXPENSE", cartao, "400.00"), dia));
        criada(ana, em(corpo("REFUND", banco, "30.00"), dia));
        criada(ana, em(com(corpo("INVESTMENT", banco, "1000.00"), "counterpartAccountId", corretora), dia));
        criada(ana, em(com(corpo("TRANSFER", banco, "400.00"), "counterpartAccountId", cartao), dia));   // paga a fatura

        Map m = mes(ana, "2026-05");

        assertThat(m).containsEntry("month", "2026-05").containsEntry("income", "5000.00")
                .containsEntry("expense", "500.00")          // 100 + 400; a fatura paga (TRANSFER) NÃO duplica
                .containsEntry("refunds", "30.00")
                .containsEntry("netExpense", "470.00")
                .containsEntry("invested", "1000.00")
                .containsEntry("net", "4530.00");            // 5000 - 470
    }

    @Test
    void distribuiPorCategoriaSomandoSubcategoriasEOrdenaPeloMaiorGasto() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        String dia = "2026-05-10T15:00:00Z";
        String restaurante = categoriaPadrao(ana, "FOOD_RESTAURANT");
        String mercado = categoriaPadrao(ana, "FOOD_GROCERIES");

        criada(ana, em(com(corpo("EXPENSE", banco, "100.00"), "categoryId", restaurante), dia));
        criada(ana, em(com(corpo("EXPENSE", banco, "50.00"), "categoryId", restaurante), dia));
        criada(ana, em(com(corpo("REFUND", banco, "20.00"), "categoryId", restaurante), dia));   // estorno abate o gasto
        criada(ana, em(com(corpo("EXPENSE", banco, "200.00"), "categoryId", mercado), dia));
        criada(ana, em(com(corpo("EXPENSE", banco, "40.00"), "categoryId", categoriaPadrao(ana, "FOOD")), dia));   // direto no pai
        criada(ana, em(com(corpo("EXPENSE", banco, "300.00"), "categoryId", categoriaPadrao(ana, "TRANSPORT")), dia));
        criada(ana, em(corpo("EXPENSE", banco, "15.00"), dia));                                   // sem categoria

        List<Map> porCategoria = (List<Map>) mes(ana, "2026-05").get("byCategory");

        assertThat(porCategoria).extracting(c -> c.get("name")).containsExactly("Alimentação", "Transporte", "Sem categoria");
        Map alimentacao = porCategoria.get(0);
        assertThat(alimentacao).containsEntry("total", "370.00")       // (100+50-20) + 200 + 40
                .containsEntry("directTotal", "40.00").containsEntry("code", "FOOD").containsEntry("system", true);
        List<Map> filhas = (List<Map>) alimentacao.get("children");
        assertThat(filhas).extracting(c -> c.get("name")).containsExactly("Mercado", "Restaurante");   // maior primeiro
        assertThat(filhas.get(0)).containsEntry("total", "200.00");
        assertThat(filhas.get(1)).containsEntry("total", "130.00");
        assertThat(porCategoria.get(2)).containsEntry("total", "15.00");
        assertThat(porCategoria.get(2).get("categoryId")).isNull();
    }

    @Test
    void distribuiPorContextoComLinhaSemContexto() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        String dia = "2026-05-10T15:00:00Z";
        criada(ana, em(com(corpo("EXPENSE", banco, "80.00"), "contextId", contextoPadrao(ana, "RELATIONSHIP")), dia));
        criada(ana, em(com(corpo("EXPENSE", banco, "20.00"), "contextId", contextoPadrao(ana, "RELATIONSHIP")), dia));
        criada(ana, em(com(corpo("EXPENSE", banco, "300.00"), "contextId", contextoPadrao(ana, "PET")), dia));
        criada(ana, em(corpo("EXPENSE", banco, "7.50"), dia));

        List<Map> porContexto = (List<Map>) mes(ana, "2026-05").get("byContext");

        assertThat(porContexto).extracting(c -> c.get("name")).containsExactly("Pet", "Relacionamento", "Sem contexto");
        assertThat(porContexto).extracting(c -> c.get("total")).containsExactly("300.00", "100.00", "7.50");
    }

    @Test
    void limiteDoMesUsaOFusoDeSaoPaulo() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        // 23:30 de 31/03 em São Paulo (UTC-3) já é 01/04 em UTC
        criada(ana, em(corpo("EXPENSE", banco, "11.00"), "2026-04-01T02:30:00Z"));
        // 00:00 de 01/04 em São Paulo
        criada(ana, em(corpo("EXPENSE", banco, "22.00"), "2026-04-01T03:00:00Z"));
        // 23:59 de 30/04 em São Paulo
        criada(ana, em(corpo("EXPENSE", banco, "33.00"), "2026-05-01T02:59:00Z"));

        assertThat(mes(ana, "2026-03")).containsEntry("expense", "11.00");
        assertThat(mes(ana, "2026-04")).containsEntry("expense", "55.00");   // 22 + 33
        assertThat(mes(ana, "2026-05")).containsEntry("expense", "0.00");
    }

    @Test
    void semParametroUsaOMesAtualEMesVazioVemZerado() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        criada(ana, corpo("EXPENSE", banco, "9.00"));   // agora

        Map atual = mes(ana, null);
        assertThat(atual).containsEntry("month", YearMonth.now(ZoneId.of("America/Sao_Paulo")).toString())
                .containsEntry("expense", "9.00");

        Map vazio = mes(ana, "2030-01");
        assertThat(vazio).containsEntry("income", "0.00").containsEntry("net", "0.00");
        assertThat((List) vazio.get("byCategory")).isEmpty();
        assertThat((List) vazio.get("byContext")).isEmpty();
    }

    @Test
    void mesInvalidoOuForaDoIntervalo() {
        Pessoa ana = pessoa();
        for (String ruim : new String[] {"2026-13", "abril", "2026-4", "26-04", "2026-04-01"}) {
            assertThat(status(HttpMethod.GET, "/api/v1/summary/monthly?month=" + ruim, null, ana)).as(ruim).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(status(HttpMethod.GET, "/api/v1/summary/monthly?month=1999-12", null, ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.GET, "/api/v1/summary/monthly?month=2101-01", null, ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void mensalContaTransacoesDeContaArquivadaMasNaoExcluidas() {
        Pessoa ana = pessoa();
        String velha = conta(ana, "Velha", "CHECKING_ACCOUNT", null);
        Map t1 = criada(ana, em(corpo("EXPENSE", velha, "10.00"), "2026-05-10T15:00:00Z"));
        Map t2 = criada(ana, em(corpo("EXPENSE", velha, "5.00"), "2026-05-11T15:00:00Z"));
        call(HttpMethod.PATCH, "/api/v1/accounts/" + velha, Map.of("archived", true), ana);
        call(HttpMethod.DELETE, "/api/v1/transactions/" + t2.get("id"), null, ana);

        assertThat(mes(ana, "2026-05")).containsEntry("expense", "10.00");   // histórico preservado; excluída fora
    }

    // ---------------------------------------------------------------- série mensal

    @Test
    void historicoTrazOsMesesEmOrdemComMesesVaziosZerados() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        String corretora = conta(ana, "Corretora", "INVESTMENT_ACCOUNT", null);
        criada(ana, em(corpo("INCOME", banco, "5000.00"), "2026-03-10T15:00:00Z"));
        criada(ana, em(corpo("EXPENSE", banco, "1200.50"), "2026-03-12T15:00:00Z"));
        criada(ana, em(corpo("REFUND", banco, "200.50"), "2026-03-20T15:00:00Z"));
        criada(ana, em(com(corpo("INVESTMENT", banco, "800.00"), "counterpartAccountId", corretora), "2026-03-21T15:00:00Z"));
        criada(ana, em(corpo("EXPENSE", banco, "300.00"), "2026-05-02T15:00:00Z"));
        criada(ana, em(com(corpo("TRANSFER", banco, "999.00"), "counterpartAccountId", corretora), "2026-05-03T15:00:00Z"));

        Map h = historico(ana, "?end=2026-05&months=4");

        assertThat(h).containsEntry("from", "2026-02").containsEntry("to", "2026-05");
        List<Map> meses = (List<Map>) h.get("months");
        assertThat(meses).extracting(m -> m.get("month")).containsExactly("2026-02", "2026-03", "2026-04", "2026-05");
        assertThat(meses.get(0)).containsEntry("income", "0.00").containsEntry("net", "0.00");   // sem movimento
        assertThat(meses.get(1)).containsEntry("income", "5000.00").containsEntry("expense", "1200.50")
                .containsEntry("refunds", "200.50").containsEntry("netExpense", "1000.00")
                .containsEntry("invested", "800.00").containsEntry("net", "4000.00");
        assertThat(meses.get(2)).containsEntry("expense", "0.00");
        assertThat(meses.get(3)).containsEntry("expense", "300.00").containsEntry("net", "-300.00");   // transferência fora
    }

    @Test
    void historicoBateComOResumoMensalDeCadaMes() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        criada(ana, em(corpo("INCOME", banco, "100.10"), "2026-01-15T15:00:00Z"));
        criada(ana, em(corpo("EXPENSE", banco, "33.33"), "2026-02-15T15:00:00Z"));
        criada(ana, em(corpo("EXPENSE", banco, "0.07"), "2026-02-16T15:00:00Z"));

        List<Map> meses = (List<Map>) historico(ana, "?end=2026-02&months=2").get("months");

        for (Map m : meses) {
            Map doMes = mes(ana, (String) m.get("month"));
            for (String campo : new String[] {"income", "expense", "refunds", "netExpense", "invested", "net"}) {
                assertThat(m.get(campo)).as(m.get("month") + "." + campo).isEqualTo(doMes.get(campo));
            }
        }
    }

    @Test
    void historicoUsaOFusoDeSaoPauloNosLimitesDoMes() {
        Pessoa ana = pessoa();
        String banco = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        criada(ana, em(corpo("EXPENSE", banco, "11.00"), "2026-04-01T02:30:00Z"));   // 31/03 23:30 em São Paulo
        criada(ana, em(corpo("EXPENSE", banco, "22.00"), "2026-04-01T03:00:00Z"));   // 01/04 00:00
        criada(ana, em(corpo("EXPENSE", banco, "33.00"), "2026-05-01T02:59:00Z"));   // 30/04 23:59

        List<Map> meses = (List<Map>) historico(ana, "?end=2026-05&months=3").get("months");

        assertThat(meses).extracting(m -> m.get("expense")).containsExactly("11.00", "55.00", "0.00");
    }

    @Test
    void historicoSemParametrosTemSeisMesesTerminandoNoMesAtual() {
        Map h = historico(pessoa(), "");

        List<Map> meses = (List<Map>) h.get("months");
        assertThat(meses).hasSize(6);
        assertThat(meses.get(5).get("month")).isEqualTo(h.get("to"));
        assertThat(meses.get(5).get("month")).isEqualTo(YearMonth.now(ZoneId.of("America/Sao_Paulo")).toString());
    }

    @Test
    void historicoIgnoraTransacaoExcluidaENaoMisturaUsuarios() {
        Pessoa ana = pessoa(), bia = pessoa();
        String contaAna = conta(ana, "Banco", "CHECKING_ACCOUNT", null);
        String contaBia = conta(bia, "Banco", "CHECKING_ACCOUNT", null);
        criada(ana, em(corpo("EXPENSE", contaAna, "10.00"), "2026-05-10T15:00:00Z"));
        Map excluida = criada(ana, em(corpo("EXPENSE", contaAna, "99.00"), "2026-05-11T15:00:00Z"));
        criada(bia, em(corpo("EXPENSE", contaBia, "777.00"), "2026-05-10T15:00:00Z"));
        call(HttpMethod.DELETE, "/api/v1/transactions/" + excluida.get("id"), null, ana);

        assertThat(((List<Map>) historico(ana, "?end=2026-05&months=1").get("months")).get(0)).containsEntry("expense", "10.00");
        assertThat(((List<Map>) historico(bia, "?end=2026-05&months=1").get("months")).get(0)).containsEntry("expense", "777.00");
    }

    @Test
    void historicoValidaOsParametros() {
        Pessoa ana = pessoa();
        for (String q : new String[] {"?months=0", "?months=25", "?end=1999-12&months=1", "?end=2101-01&months=1"}) {
            assertThat(status(HttpMethod.GET, "/api/v1/summary/history" + q, null, ana)).as(q).isIn(HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.BAD_REQUEST);
        }
        assertThat(status(HttpMethod.GET, "/api/v1/summary/history?end=2000-01&months=24", null, ana)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status(HttpMethod.GET, "/api/v1/summary/history?months=24", null, ana)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void openApiDocumentaOsEndpointsDeResumo() {
        Map paths = (Map) rest.getForEntity("/api/v1/openapi", Map.class).getBody().get("paths");
        assertThat(paths).containsKeys("/api/v1/summary", "/api/v1/summary/monthly", "/api/v1/summary/history");
    }
}
