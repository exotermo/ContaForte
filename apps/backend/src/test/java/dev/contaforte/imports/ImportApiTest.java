package dev.contaforte.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.contaforte.ApiTestSupport;
import dev.contaforte.users.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@SuppressWarnings({"rawtypes", "unchecked"})
class ImportApiTest extends ApiTestSupport {

    @Autowired ImportService importService;
    @Autowired MeterRegistry metrics;

    // ---------------------------------------------------------------- apoio

    private static byte[] fixture(String name) {
        String dir = System.getenv("FIXTURES_DIR");
        try {
            return Files.readAllBytes(Path.of(dir != null ? dir : "../../datasets/synthetic/imports").resolve(name));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private ResponseEntity<Map> upload(Pessoa p, String filename, byte[] content, Object accountId, Map<String, String> extra) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        if (content != null) {
            body.add("file", new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
        }
        if (accountId != null) body.add("accountId", accountId.toString());
        if (extra != null) extra.forEach(body::add);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (p != null) h.setBearerAuth(p.token());
        return rest.exchange("/api/v1/imports", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private Map previa(Pessoa p, String fixture, Object conta) {
        var r = upload(p, fixture, fixture(fixture), conta, null);
        assertThat(r.getStatusCode()).as("prévia de %s: %s", fixture, r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private ResponseEntity<Map> confirmar(Pessoa p, Object id, Map<String, Object> corpo) {
        return call(HttpMethod.POST, "/api/v1/imports/" + id + "/confirm", corpo, p);
    }

    private Map confirmada(Pessoa p, Object id) {
        var r = confirmar(p, id, null);
        assertThat(r.getStatusCode()).as("confirmar: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private List<Map> linhas(Pessoa p, Object id, String query) {
        var r = call(HttpMethod.GET, "/api/v1/imports/" + id + "/items" + query, null, p);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (List<Map>) r.getBody().get("items");
    }

    private Map transacoes(Pessoa p, String query) {
        var r = call(HttpMethod.GET, "/api/v1/transactions" + query, null, p);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static String ofxMinimo(int n) {
        StringBuilder sb = new StringBuilder("OFXHEADER:100\nCHARSET:NONE\n\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL<BANKACCTFROM><ACCTID>1</BANKACCTFROM><BANKTRANLIST>");
        for (int i = 0; i < n; i++) {
            sb.append("<STMTTRN><DTPOSTED>2026031").append(i % 9).append("<TRNAMT>-").append(i % 900 + 1).append(".50<FITID>R").append(i)
                    .append("<MEMO>COMPRA ").append(i).append("</STMTTRN>");
        }
        return sb.append("</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>").toString();
    }

    private double contador(String nome, String... tags) {
        return metrics.counter(nome, tags).count();
    }

    // ---------------------------------------------------------------- acesso

    @Test
    void semTokenEServicoEDispositivoNaoImportam() {
        assertThat(status(HttpMethod.GET, "/api/v1/imports", null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(upload(null, "a.ofx", fixture("conta-corrente-1.ofx"), UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (Role role : new Role[] {Role.SERVICE, Role.DEVICE}) {
            Pessoa p = pessoa(role);
            assertThat(status(HttpMethod.GET, "/api/v1/imports", null, p)).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(upload(p, "a.ofx", fixture("conta-corrente-1.ofx"), UUID.randomUUID(), null).getStatusCode()).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------- o fluxo completo: prévia → confirmar → desfazer

    @Test
    void previaNaoCriaTransacaoAlgumaEMostraAsLinhas() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);

        Map p = previa(ana, "conta-corrente-1.ofx", conta);

        assertThat(p).containsEntry("status", "PREVIEW").containsEntry("format", "OFX").containsEntry("accountId", conta)
                .containsEntry("totalRows", 12).containsEntry("newRows", 12).containsEntry("duplicateRows", 0)
                .containsEntry("possibleRows", 0).containsEntry("invalidRows", 0).containsEntry("importedRows", 0)
                .containsEntry("fileAlreadyImported", false).containsEntry("fileName", "conta-corrente-1.ofx");
        assertThat((List) p.get("warnings")).isEmpty();
        assertThat(p.get("expiresAt")).isNotNull();
        assertThat(p).doesNotContainKey("confirmedAt");
        assertThat(((Number) transacoes(ana, "").get("totalElements")).intValue()).isZero();   // nada foi criado
        assertThat(saldo(ana, conta)).isEqualTo("0.00");

        List<Map> itens = linhas(ana, p.get("id"), "?size=100");
        assertThat(itens).hasSize(12);
        assertThat(itens.get(0)).containsEntry("lineNo", 1).containsEntry("status", "NEW").containsEntry("type", "EXPENSE")
                .containsEntry("amount", "187.45").containsEntry("description", "COMPRA CARTAO MERCADO EXEMPLO")
                .containsEntry("merchant", "Mercado Exemplo").containsEntry("selected", true);
        assertThat(itens.get(2)).containsEntry("type", "INCOME").containsEntry("amount", "6500.00");
    }

    @Test
    void confirmarCriaAsTransacoesComOrigemEImportacaoEOSaldoBate() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);

        Map c = confirmada(ana, p.get("id"));

        assertThat(c).containsEntry("status", "CONFIRMED").containsEntry("importedRows", 12).containsEntry("skippedByConflict", 0);
        assertThat(c.get("confirmedAt")).isNotNull();
        assertThat(saldo(ana, conta)).isEqualTo("4179.53");   // líquido do extrato

        Map lista = transacoes(ana, "?importId=" + p.get("id") + "&size=100");
        assertThat(lista.get("totalElements")).isEqualTo(12);
        for (Map t : (List<Map>) lista.get("items")) {
            assertThat(t).containsEntry("source", "OFX").containsEntry("importId", p.get("id")).containsEntry("accountId", conta);
            // M4: as regras padrão categorizam o que reconhecem (posto, farmácia, aluguel...); o resto fica sem categoria
            if (t.get("categoryId") != null) {
                assertThat(t).containsEntry("classificationStatus", "AUTO").containsEntry("classificationSource", "DEFAULT_RULE");
            } else {
                assertThat(t).containsEntry("classificationStatus", "NONE");
            }
        }
        assertThat(((List<Map>) lista.get("items")).stream().filter(t -> t.get("categoryId") != null).count()).isBetween(5L, 11L);
        assertThat(((List<Map>) lista.get("items")).stream().map(t -> t.get("amount"))).contains("187.45", "6500.00");
        assertThat(transacoes(ana, "?importId=" + UUID.randomUUID()).get("totalElements")).isEqualTo(0);   // filtro isola por importação
    }

    @Test
    void confirmarDuasVezesDa409ENaoDuplica() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        confirmada(ana, p.get("id"));

        assertThat(confirmar(ana, p.get("id"), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(saldo(ana, conta)).isEqualTo("4179.53");
        assertThat(transacoes(ana, "").get("totalElements")).isEqualTo(12);
    }

    @Test
    void desfazerExcluiLogicamenteEOSaldoVoltaMasOHistoricoFica() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", "100.00");
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        confirmada(ana, p.get("id"));
        assertThat(saldo(ana, conta)).isEqualTo("4279.53");

        var r = call(HttpMethod.POST, "/api/v1/imports/" + p.get("id") + "/rollback", null, ana);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "ROLLED_BACK").containsEntry("importedRows", 12);
        assertThat(r.getBody().get("rolledBackAt")).isNotNull();
        assertThat(saldo(ana, conta)).isEqualTo("100.00");
        assertThat(transacoes(ana, "").get("totalElements")).isEqualTo(0);
        // o histórico e as linhas continuam
        assertThat(call(HttpMethod.GET, "/api/v1/imports/" + p.get("id"), null, ana).getBody()).containsEntry("status", "ROLLED_BACK");
        assertThat(linhas(ana, p.get("id"), "?size=100")).hasSize(12);
        // as linhas existem no banco, só excluídas logicamente
        assertThat(jdbc.queryForObject("select count(*) from transactions where import_id = ?::uuid and deleted_at is not null", Integer.class, p.get("id").toString())).isEqualTo(12);
        // desfazer de novo: conflito
        assertThat(call(HttpMethod.POST, "/api/v1/imports/" + p.get("id") + "/rollback", null, ana).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void depoisDeDesfazerOMesmoArquivoPodeSerImportadoDeNovo() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map primeira = previa(ana, "conta-corrente-1.ofx", conta);
        confirmada(ana, primeira.get("id"));
        // com o arquivo já importado, a nova prévia avisa e marca tudo como duplicata
        Map duplicada = previa(ana, "conta-corrente-1.ofx", conta);
        assertThat(duplicada).containsEntry("fileAlreadyImported", true).containsEntry("duplicateRows", 12).containsEntry("newRows", 0);

        call(HttpMethod.POST, "/api/v1/imports/" + primeira.get("id") + "/rollback", null, ana);
        Map terceira = previa(ana, "conta-corrente-1.ofx", conta);

        assertThat(terceira).containsEntry("fileAlreadyImported", false).containsEntry("newRows", 12);
        confirmada(ana, terceira.get("id"));
        assertThat(saldo(ana, conta)).isEqualTo("4179.53");   // uma vez só
    }

    @Test
    void arquivoSobrepostoDuplicaOQueJaExisteEImportaSoAsNovas() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        confirmada(ana, previa(ana, "conta-corrente-1.ofx", conta).get("id"));

        Map segunda = previa(ana, "conta-corrente-2.ofx", conta);

        assertThat(segunda).containsEntry("totalRows", 7).containsEntry("duplicateRows", 4).containsEntry("newRows", 3);
        Map c = confirmada(ana, segunda.get("id"));
        assertThat(c).containsEntry("importedRows", 3);
        assertThat(saldo(ana, conta)).isEqualTo("3954.62");   // 4179,53 - 200,00 - 89,90 + 64,99
    }

    // ---------------------------------------------------------------- decisão do usuário sobre as linhas

    @Test
    void possivelDuplicataSoEntraSeOUsuarioAceitar() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map lancamento = corpo("EXPENSE", conta, "64.99");
        lancamento.put("occurredAt", "2026-03-18T15:00:00Z");
        lancamento.put("description", "remédio");
        criada(ana, lancamento);

        Map sem = previa(ana, "conta-corrente-1.ofx", conta);
        assertThat(sem).containsEntry("possibleRows", 1).containsEntry("newRows", 11);
        Map possivel = linhas(ana, sem.get("id"), "?status=POSSIBLE_DUPLICATE").get(0);
        assertThat(possivel).containsEntry("selected", false).containsEntry("amount", "64.99");
        assertThat((Map) possivel.get("existing")).containsEntry("amount", "64.99").containsEntry("description", "remédio").containsEntry("source", "MANUAL");

        assertThat(confirmada(ana, sem.get("id"))).containsEntry("importedRows", 11);   // sem aceitar: só as 11 novas

        // outra prévia: aceitando a possível duplicata (a de 64,99 já foi importada por FITID? não: o FITID10 é novo aqui)
        Pessoa bia = pessoa();
        String contaBia = conta(bia, "Corrente", "CHECKING_ACCOUNT", null);
        Map l2 = corpo("EXPENSE", contaBia, "64.99");
        l2.put("occurredAt", "2026-03-18T15:00:00Z");
        l2.put("description", "remédio");
        criada(bia, l2);
        Map com = previa(bia, "conta-corrente-1.ofx", contaBia);
        Object idPossivel = linhas(bia, com.get("id"), "?status=POSSIBLE_DUPLICATE").get(0).get("id");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("includePossible", List.of(idPossivel));
        var r = confirmar(bia, com.get("id"), corpo);
        assertThat(r.getBody()).containsEntry("importedRows", 12);
    }

    @Test
    void usuarioPodeExcluirLinhasNovasDaConfirmacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        List<Map> itens = linhas(ana, p.get("id"), "?size=100");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("exclude", List.of(itens.get(0).get("id"), itens.get(2).get("id")));   // a compra de 187,45 e o salário

        Map c = confirmar(ana, p.get("id"), corpo).getBody();

        assertThat(c).containsEntry("importedRows", 10);
        assertThat(saldo(ana, conta)).isEqualTo("-2133.02");   // 4179,53 + 187,45 (despesa excluída) - 6500,00 (salário excluído)
        // as linhas excluídas ficam registradas como não selecionadas
        assertThat(linhas(ana, p.get("id"), "?size=100").stream().filter(i -> Boolean.FALSE.equals(i.get("selected"))).count()).isEqualTo(2);
    }

    @Test
    void listasDeConfirmacaoComIdsInvalidosSaoRecusadas() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        Object idNova = linhas(ana, p.get("id"), "?size=1").get(0).get("id");
        Map<String, Object> incluirNova = new HashMap<>(Map.of("includePossible", List.of(idNova)));
        Map<String, Object> incluirInexistente = new HashMap<>(Map.of("includePossible", List.of(UUID.randomUUID().toString())));
        Map<String, Object> excluirInexistente = new HashMap<>(Map.of("exclude", List.of(UUID.randomUUID().toString())));

        assertThat(confirmar(ana, p.get("id"), incluirNova).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);      // uma nova não é "possível"
        assertThat(confirmar(ana, p.get("id"), incluirInexistente).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(confirmar(ana, p.get("id"), excluirInexistente).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // ids de outra importação também não valem
        Map outra = previa(ana, "conta-corrente-2.ofx", conta);
        Object idDaOutra = linhas(ana, outra.get("id"), "?size=1").get(0).get("id");
        assertThat(confirmar(ana, p.get("id"), new HashMap<>(Map.of("exclude", List.of(idDaOutra)))).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(saldo(ana, conta)).isEqualTo("0.00");   // nada foi criado por nenhuma tentativa recusada
    }

    // ---------------------------------------------------------------- CSV

    @Test
    void csvSemMapeamentoUsaOPalpitePeloCabecalho() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);

        Map p = previa(ana, "banco-a.csv", conta);

        assertThat(p).containsEntry("format", "CSV").containsEntry("newRows", 12);
        assertThat((Map) p.get("mapping")).containsEntry("dateColumn", "Data").containsEntry("amountColumn", "Valor");
        confirmada(ana, p.get("id"));
        assertThat(saldo(ana, conta)).isEqualTo("4179.53");
        Map t = ((List<Map>) transacoes(ana, "?importId=" + p.get("id")).get("items")).get(0);
        assertThat(t).containsEntry("source", "CSV");
    }

    @Test
    void sugestaoDeMapeamentoDevolveDelimitadorCabecalhoEAmostra() {
        Pessoa ana = pessoa();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fixture("banco-c.csv")) {
            @Override
            public String getFilename() {
                return "c.csv";
            }
        });
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(ana.token());

        var r = rest.exchange("/api/v1/imports/suggest-mapping", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("delimiter", "\t").containsEntry("skipLines", 3).containsEntry("hasHeader", true);
        assertThat((List) r.getBody().get("headers")).containsExactly("Data", "Lançamento", "Tipo", "Valor");
        assertThat((List) r.getBody().get("sample")).hasSize(5);
        assertThat((Map) r.getBody().get("mapping")).containsEntry("typeColumn", "Tipo");
    }

    @Test
    void csvComMapeamentoExplicitoAceitaNumerosEBooleanosNoJson() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String mapa = "{\"delimiter\":\"TAB\",\"skipLines\":3,\"hasHeader\":true,\"dateColumn\":\"Data\",\"dateFormat\":\"DMY_SLASH_SHORT\","
                + "\"amountColumn\":\"Valor\",\"typeColumn\":\"Tipo\",\"descriptionColumn\":\"Lançamento\",\"invertSign\":false}";

        var r = upload(ana, "c.csv", fixture("banco-c.csv"), conta, Map.of("mapping", mapa));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("newRows", 12);
        assertThat((Map) r.getBody().get("mapping")).containsEntry("skipLines", "3").containsEntry("delimiter", "TAB");
    }

    @Test
    void csvComMapeamentoRuimOuSemPalpiteEhRecusado() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        byte[] csv = fixture("banco-a.csv");

        assertThat(upload(ana, "a.csv", csv, conta, Map.of("mapping", "{\"dateColumn\":\"Quando\",\"amountColumn\":\"Valor\"}")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(ana, "a.csv", csv, conta, Map.of("mapping", "isto não é json")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(ana, "a.csv", csv, conta, Map.of("mapping", "[1,2]")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(ana, "a.csv", csv, conta, Map.of("mapping", "{\"dateColumn\":{\"x\":1}}")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(ana, "a.csv", csv, conta, Map.of("mapping", "{\"campoInventado\":\"x\"}")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(ana, "x.csv", "alfa;beta\n1;2\n".getBytes(StandardCharsets.UTF_8), conta, null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);   // sem palpite
        assertThat(((Number) call(HttpMethod.GET, "/api/v1/imports", null, ana).getBody().get("totalElements")).intValue()).isZero();   // nenhuma prévia ficou
    }

    @Test
    void csvComLinhasRuinsMostraOMotivoEImportaSoAsBoas() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        var r = upload(ana, "ruim.csv", fixture("banco-a-invalido.csv"), conta, Map.of("mapping",
                "{\"delimiter\":\";\",\"dateColumn\":\"Data\",\"amountColumn\":\"Valor\",\"descriptionColumn\":\"Histórico\"}"));

        assertThat(r.getBody()).containsEntry("invalidRows", 8).containsEntry("newRows", 2);
        List<Map> invalidas = linhas(ana, r.getBody().get("id"), "?status=INVALID");
        assertThat(invalidas).hasSize(8).allSatisfy(i -> {
            assertThat((String) i.get("reason")).isNotBlank();
            assertThat(i.get("selected")).isEqualTo(false);
        });
        assertThat(confirmada(ana, r.getBody().get("id"))).containsEntry("importedRows", 2);
        assertThat(saldo(ana, conta)).isEqualTo("-30.50");   // -10,00 e -20,50
    }

    // ---------------------------------------------------------------- deduplicação cruzada pela API

    @Test
    void csvDoBancoBContraOfxJaImportadoMostraProvaveisEPossiveisComOLancamentoExistente() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        confirmada(ana, previa(ana, "conta-corrente-1.ofx", conta).get("id"));

        Map b = previa(ana, "banco-b.csv", conta);

        assertThat(b).containsEntry("duplicateRows", 3).containsEntry("possibleRows", 9).containsEntry("newRows", 0);
        List<Map> possiveis = linhas(ana, b.get("id"), "?status=POSSIBLE_DUPLICATE&size=100");
        assertThat(possiveis).hasSize(9).allSatisfy(i -> {
            assertThat(i.get("existing")).isNotNull();
            assertThat((String) i.get("reason")).contains("data próxima");
            assertThat(((Map) i.get("existing")).get("source")).isEqualTo("OFX");
            assertThat(((Map) i.get("existing")).get("amount")).isEqualTo(i.get("amount"));
        });
        assertThat(confirmada(ana, b.get("id"))).containsEntry("importedRows", 0);   // nada marcado: nada importado
        assertThat(saldo(ana, conta)).isEqualTo("4179.53");
    }

    @Test
    void duasPreviasDoMesmoArquivoConfirmadasEmSequenciaNaoDuplicamPorIdentificador() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map a = previa(ana, "conta-corrente-1.ofx", conta);
        Map b = previa(ana, "conta-corrente-1.ofx", conta);   // as duas nasceram com 12 novas
        assertThat(a).containsEntry("newRows", 12);
        assertThat(b).containsEntry("newRows", 12);

        confirmada(ana, a.get("id"));
        Map segunda = confirmada(ana, b.get("id"));

        assertThat(segunda).containsEntry("importedRows", 0).containsEntry("skippedByConflict", 12).containsEntry("duplicateRows", 12).containsEntry("newRows", 0);
        assertThat(saldo(ana, conta)).isEqualTo("4179.53");   // não dobrou
        assertThat(transacoes(ana, "").get("totalElements")).isEqualTo(12);
        assertThat(linhas(ana, b.get("id"), "?status=DUPLICATE&size=100")).hasSize(12);
    }

    @Test
    void confirmacoesSimultaneasDeUmCsvSemIdentificadorCriamUmaVezSo() throws Exception {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "banco-c.csv", conta);   // sem identificador: só o bloqueio da prévia impede o dobro
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<HttpStatus> resultados = new ArrayList<>();
        try {
            List<Callable<HttpStatus>> tarefas = new ArrayList<>();
            for (int i = 0; i < 8; i++) tarefas.add(() -> HttpStatus.valueOf(confirmar(ana, p.get("id"), null).getStatusCode().value()));
            for (Future<HttpStatus> f : pool.invokeAll(tarefas)) resultados.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(resultados.stream().filter(s -> s == HttpStatus.OK).count()).isEqualTo(1);
        assertThat(resultados.stream().filter(s -> s == HttpStatus.CONFLICT).count()).isEqualTo(7);
        assertThat(transacoes(ana, "").get("totalElements")).isEqualTo(12);
        assertThat(saldo(ana, conta)).isEqualTo("4179.53");
    }

    // ---------------------------------------------------------------- arquivos recusados

    @Test
    void arquivosQueNaoSaoExtratoOuTemProblemaDeFormatoSaoRecusadosSemCriarPrevia() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map<String, byte[]> ruins = new java.util.LinkedHashMap<>();
        ruins.put("moeda", fixture("moeda-usd.ofx"));
        ruins.put("duas contas", fixture("duas-contas.ofx"));
        ruins.put("sem transações", fixture("sem-transacoes.ofx"));
        ruins.put("zip", new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0});
        ruins.put("pdf", "%PDF-1.7\n\0\0".getBytes(StandardCharsets.ISO_8859_1));
        ruins.put("texto solto", "olá, isto não é um extrato".getBytes(StandardCharsets.UTF_8));
        ruins.put("ofx sem tag", "OFXHEADER:100\nnada\n".getBytes(StandardCharsets.UTF_8));
        for (var e : ruins.entrySet()) {
            var r = upload(ana, "x.dat", e.getValue(), conta, null);
            assertThat(r.getStatusCode()).as(e.getKey() + ": " + r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat((String) r.getBody().get("detail")).isNotBlank();
        }
        assertThat(((Number) call(HttpMethod.GET, "/api/v1/imports", null, ana).getBody().get("totalElements")).intValue()).isZero();
    }

    @Test
    void formatoDeclaradoDiferenteDoConteudoEhRecusado() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);

        assertThat(upload(ana, "a.ofx", fixture("banco-a.csv"), conta, Map.of("format", "OFX")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(ana, "a.csv", fixture("conta-corrente-1.ofx"), conta, Map.of("format", "CSV")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(ana, "a.csv", fixture("conta-corrente-1.ofx"), conta, Map.of("format", "PDF")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // o nome e a extensão não decidem nada: o conteúdo OFX com nome .csv é lido como OFX
        assertThat(upload(ana, "extrato.csv", fixture("conta-corrente-1.ofx"), conta, null).getBody()).containsEntry("format", "OFX");
    }

    @Test
    void parteDoFormularioAusenteOuInvalidaDa400() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);

        assertThat(upload(ana, "a.ofx", null, conta, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);                     // sem arquivo
        assertThat(upload(ana, "a.ofx", fixture("conta-corrente-1.ofx"), null, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);  // sem conta
        assertThat(upload(ana, "a.ofx", fixture("conta-corrente-1.ofx"), "nao-eh-uuid", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(ana, "vazio.ofx", new byte[0], conta, null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void arquivoMaiorQueOLimiteDa413EMuitasLinhasDa422() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);

        byte[] grande = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(grande, (byte) 'a');
        assertThat(upload(ana, "grande.ofx", grande, conta, null).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(upload(ana, "muitas.ofx", ofxMinimo(20_001).getBytes(StandardCharsets.UTF_8), conta, null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void tresMilLinhasViramPreviaEmPoucosSegundos() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        var r = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> upload(ana, "tres-mil.ofx", ofxMinimo(3000).getBytes(StandardCharsets.UTF_8), conta, null));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("totalRows", 3000).containsEntry("newRows", 3000);
        assertThat(confirmada(ana, r.getBody().get("id"))).containsEntry("importedRows", 3000);
    }

    // ---------------------------------------------------------------- conta

    @Test
    void contaDeOutroUsuarioInexistenteOuArquivadaNaoRecebeImportacao() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String daAna = conta(ana, "Da Ana", "CHECKING_ACCOUNT", null);
        String velha = conta(ana, "Velha", "CHECKING_ACCOUNT", null);
        call(HttpMethod.PATCH, "/api/v1/accounts/" + velha, Map.of("archived", true), ana);

        assertThat(upload(bia, "a.ofx", fixture("conta-corrente-1.ofx"), daAna, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upload(ana, "a.ofx", fixture("conta-corrente-1.ofx"), UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upload(ana, "a.ofx", fixture("conta-corrente-1.ofx"), velha, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void contaArquivadaDepoisDaPreviaImpedeAConfirmacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        call(HttpMethod.PATCH, "/api/v1/accounts/" + conta, Map.of("archived", true), ana);

        assertThat(confirmar(ana, p.get("id"), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------------- isolamento

    @Test
    void importacaoDeOutroUsuarioEInvisivelEmTodaRota() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        String url = "/api/v1/imports/" + p.get("id");

        assertThat(status(HttpMethod.GET, url, null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, url + "/items", null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, url + "/confirm", null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, url + "/rollback", null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.DELETE, url, null, bia)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, "/api/v1/imports/" + UUID.randomUUID(), null, bia)).as("inexistente responde igual").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/imports", null, bia).getBody().get("totalElements")).isEqualTo(0);
        // intacta para a Ana
        assertThat(call(HttpMethod.GET, url, null, ana).getBody()).containsEntry("status", "PREVIEW");
        assertThat(saldo(ana, conta)).isEqualTo("0.00");
    }

    // ---------------------------------------------------------------- consulta e histórico

    @Test
    void historicoListaDaMaisRecenteParaAMaisAntigaComPaginacao() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map a = previa(ana, "conta-corrente-1.ofx", conta);
        Map b = previa(ana, "banco-a.csv", conta);
        Map c = previa(ana, "fatura-cartao.ofx", conta);

        Map h = call(HttpMethod.GET, "/api/v1/imports?size=2", null, ana).getBody();

        assertThat(h).containsEntry("totalElements", 3).containsEntry("totalPages", 2).containsEntry("size", 2);
        assertThat(((List<Map>) h.get("items")).stream().map(i -> i.get("id"))).containsExactly(c.get("id"), b.get("id"));
        assertThat(((List<Map>) call(HttpMethod.GET, "/api/v1/imports?size=2&page=1", null, ana).getBody().get("items")).get(0)).containsEntry("id", a.get("id"));
    }

    @Test
    void paginacaoDasLinhasEValidada() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        String url = "/api/v1/imports/" + p.get("id") + "/items";

        assertThat(linhas(ana, p.get("id"), "?size=5&page=2")).hasSize(2).extracting(i -> i.get("lineNo")).containsExactly(11, 12);
        for (String ruim : new String[] {"?size=0", "?size=201", "?page=-1", "?status=INVENTADO"}) {
            assertThat(status(HttpMethod.GET, url + ruim, null, ana)).as(ruim).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(status(HttpMethod.GET, "/api/v1/imports?size=101", null, ana)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- descartar e expirar

    @Test
    void descartarApagaAPreviaMasNaoUmaImportacaoConfirmada() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);

        assertThat(status(HttpMethod.DELETE, "/api/v1/imports/" + p.get("id"), null, ana)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(HttpMethod.GET, "/api/v1/imports/" + p.get("id"), null, ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from import_items where import_id = ?::uuid", Integer.class, p.get("id").toString())).isZero();

        Map q = previa(ana, "conta-corrente-1.ofx", conta);
        confirmada(ana, q.get("id"));
        assertThat(status(HttpMethod.DELETE, "/api/v1/imports/" + q.get("id"), null, ana)).isEqualTo(HttpStatus.CONFLICT);
        call(HttpMethod.POST, "/api/v1/imports/" + q.get("id") + "/rollback", null, ana);
        assertThat(status(HttpMethod.DELETE, "/api/v1/imports/" + q.get("id"), null, ana)).as("desfeita também fica no histórico").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void previaVencidaNaoPodeSerConfirmadaEALimpezaARemove() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map p = previa(ana, "conta-corrente-1.ofx", conta);
        jdbc.update("update imports set expires_at = now() - interval '1 hour' where id = ?::uuid", p.get("id").toString());

        assertThat(confirmar(ana, p.get("id"), null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(saldo(ana, conta)).isEqualTo("0.00");

        assertThat(importService.purgeExpiredPreviews()).isGreaterThanOrEqualTo(1);
        assertThat(status(HttpMethod.GET, "/api/v1/imports/" + p.get("id"), null, ana)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from import_items where import_id = ?::uuid", Integer.class, p.get("id").toString())).isZero();
    }

    @Test
    void limpezaNaoTocaEmImportacoesConfirmadasNemEmPreviasValidas() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        Map valida = previa(ana, "conta-corrente-1.ofx", conta);
        Map confirmada = previa(ana, "conta-corrente-2.ofx", conta);
        confirmada(ana, confirmada.get("id"));

        importService.purgeExpiredPreviews();

        assertThat(status(HttpMethod.GET, "/api/v1/imports/" + valida.get("id"), null, ana)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/imports/" + confirmada.get("id"), null, ana)).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- limite de uso

    @Test
    void ovigesimoPrimeiroEnvioNaHoraDa429ComRetryAfterSemAfetarOutroUsuario() {
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        String contaBia = conta(bia, "Corrente", "CHECKING_ACCOUNT", null);
        byte[] pequeno = ofxMinimo(1).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < 20; i++) {
            assertThat(upload(ana, "p" + i + ".ofx", pequeno, conta, null).getStatusCode()).as("envio %d", i + 1).isEqualTo(HttpStatus.CREATED);
        }
        var bloqueado = upload(ana, "p21.ofx", pequeno, conta, null);

        assertThat(bloqueado.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(bloqueado.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 3600L);
        assertThat(upload(bia, "p.ofx", pequeno, contaBia, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // descartar prévias não devolve o limite: o contador conta tentativas
        Object id = ((List<Map>) call(HttpMethod.GET, "/api/v1/imports?size=1", null, ana).getBody().get("items")).get(0).get("id");
        call(HttpMethod.DELETE, "/api/v1/imports/" + id, null, ana);
        assertThat(upload(ana, "p22.ofx", pequeno, conta, null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ---------------------------------------------------------------- privacidade: auditoria e métricas

    @Test
    void auditoriaEMetricasGuardamSoContagensNuncaNomeDeArquivoDescricaoOuValor() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        double previasAntes = contador("contaforte.imports", "format", "OFX", "status", "PREVIEW");
        double confirmadasAntes = contador("contaforte.imports", "format", "OFX", "status", "CONFIRMED");
        double desfeitasAntes = contador("contaforte.imports", "format", "OFX", "status", "ROLLED_BACK");
        double importadasAntes = contador("contaforte.import.rows", "result", "IMPORTED");
        double novasAntes = contador("contaforte.import.rows", "result", "NEW");

        var r = upload(ana, "C:\\Users\\maria_da_silva\\extrato_maria_da_silva_cpf.ofx", fixture("conta-corrente-1.ofx"), conta, null);
        assertThat(r.getBody()).containsEntry("fileName", "extrato_maria_da_silva_cpf.ofx");   // sem caminho
        confirmada(ana, r.getBody().get("id"));
        call(HttpMethod.POST, "/api/v1/imports/" + r.getBody().get("id") + "/rollback", null, ana);

        List<Map<String, Object>> linhas = jdbc.queryForList("select action, resource, detail::text as detail from audit_logs where actor_id = ? and action like 'IMPORT_%' order by occurred_at, id", ana.id());
        assertThat(linhas).extracting(l -> l.get("action")).containsExactly("IMPORT_PREVIEWED", "IMPORT_CONFIRMED", "IMPORT_ROLLED_BACK");
        for (var l : linhas) {
            String tudo = l.get("resource") + " " + l.get("detail");
            assertThat(tudo).doesNotContainIgnoringCase("maria").doesNotContainIgnoringCase("silva").doesNotContain("cpf", "extrato", ".ofx")
                    .doesNotContain("MERCADO", "187.45", "6500", "FIT0001", "EXEMPLO");
            assertThat((String) l.get("resource")).isEqualTo("import:" + r.getBody().get("id"));
        }
        assertThat((String) linhas.get(0).get("detail")).contains("\"new\": 12").contains("\"format\": \"OFX\"");
        assertThat((String) linhas.get(1).get("detail")).contains("\"imported\": 12");
        assertThat((String) linhas.get(2).get("detail")).contains("\"transactions\": 12");

        assertThat(contador("contaforte.imports", "format", "OFX", "status", "PREVIEW")).isEqualTo(previasAntes + 1);
        assertThat(contador("contaforte.imports", "format", "OFX", "status", "CONFIRMED")).isEqualTo(confirmadasAntes + 1);
        assertThat(contador("contaforte.imports", "format", "OFX", "status", "ROLLED_BACK")).isEqualTo(desfeitasAntes + 1);
        assertThat(contador("contaforte.import.rows", "result", "IMPORTED")).isEqualTo(importadasAntes + 12);
        assertThat(contador("contaforte.import.rows", "result", "NEW")).isEqualTo(novasAntes + 12);
        // nenhuma tag de métrica da importação carrega nome de arquivo, descrição ou valor
        assertThat(metrics.getMeters().stream().filter(m -> m.getId().getName().startsWith("contaforte.import"))
                .flatMap(m -> m.getId().getTags().stream()).map(t -> t.getValue()))
                .noneMatch(v -> v.toLowerCase().contains("maria") || v.contains("EXEMPLO") || v.contains(".ofx"));
    }

    @Test
    void metricasDeImportacaoExistemEmZeroDesdeAPartidaParaTodasAsSeries() {
        for (String f : new String[] {"OFX", "CSV", "UNKNOWN"}) {
            for (String s : new String[] {"PREVIEW", "CONFIRMED", "ROLLED_BACK", "DISCARDED", "EXPIRED", "REJECTED"}) {
                assertThat(metrics.find("contaforte.imports").tag("format", f).tag("status", s).counter()).as(f + "/" + s).isNotNull();
            }
        }
        for (String r : new String[] {"NEW", "DUPLICATE", "POSSIBLE_DUPLICATE", "INVALID", "IMPORTED"}) {
            assertThat(metrics.find("contaforte.import.rows").tag("result", r).counter()).as(r).isNotNull();
        }
        assertThat(metrics.find("contaforte.import.preview").tag("format", "OFX").timer()).isNotNull();
        assertThat(metrics.find("contaforte.import.preview").tag("format", "CSV").timer()).isNotNull();
    }

    @Test
    void arquivoRecusadoContaComoRejeitadoSemGuardarNada() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        double antes = contador("contaforte.imports", "format", "OFX", "status", "REJECTED");

        upload(ana, "usd.ofx", fixture("moeda-usd.ofx"), conta, null);

        assertThat(contador("contaforte.imports", "format", "OFX", "status", "REJECTED")).isEqualTo(antes + 1);
    }

    @Test
    void transacoesImportadasPublicamOEventoDeDominioEContamNaMetricaPorTipo() {
        Pessoa ana = pessoa();
        String conta = conta(ana, "Corrente", "CHECKING_ACCOUNT", null);
        double despesasAntes = contador("contaforte.transactions.recorded", "type", "EXPENSE");
        double receitasAntes = contador("contaforte.transactions.recorded", "type", "INCOME");

        confirmada(ana, previa(ana, "conta-corrente-1.ofx", conta).get("id"));

        assertThat(contador("contaforte.transactions.recorded", "type", "EXPENSE")).isEqualTo(despesasAntes + 9);   // 9 despesas e 3 receitas no extrato
        assertThat(contador("contaforte.transactions.recorded", "type", "INCOME")).isEqualTo(receitasAntes + 3);
    }

    @Test
    void openApiDocumentaAsRotasDeImportacao() {
        Map paths = (Map) rest.getForEntity("/api/v1/openapi", Map.class).getBody().get("paths");
        assertThat(paths).containsKeys("/api/v1/imports", "/api/v1/imports/{id}", "/api/v1/imports/{id}/items",
                "/api/v1/imports/{id}/confirm", "/api/v1/imports/{id}/rollback", "/api/v1/imports/suggest-mapping");
    }
}
