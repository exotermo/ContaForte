package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.imports.parse.ImportParseException.Reason;
import dev.contaforte.transactions.TransactionType;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvParserTest {

    private final CsvParser parser = new CsvParser(Fixtures.CLOCK);
    private final OfxParser ofx = new OfxParser(Fixtures.CLOCK);

    private static CsvMapping mapping(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return CsvMapping.fromMap(m);
    }

    private ParseResult parse(String csv, CsvMapping m) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8), m);
    }

    private static final CsvMapping SIMPLE = mapping("delimiter", ";", "dateColumn", "Data", "dateFormat", "DMY_SLASH",
            "amountColumn", "Valor", "descriptionColumn", "Descrição");

    // ---------------------------------------------------------------- fixtures: os três estilos de CSV

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void leOBancoAComVirgulaDecimalEMilharComPonto() {
        CsvSuggestion s = parser.suggest(Fixtures.bytes("banco-a.csv"));
        ParseResult r = parser.parse(Fixtures.bytes("banco-a.csv"), CsvMapping.fromMap(s.mapping()));

        assertThat(r.rows()).hasSize(12);
        assertThat(r.invalidCount()).isZero();
        ParsedRow primeira = r.rows().get(0);
        assertThat(primeira.lineNo()).isEqualTo(2);   // a linha 1 é o cabeçalho
        assertThat(primeira.occurredAt()).isEqualTo(at("2026-03-02T15:00:00Z"));
        assertThat(primeira.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(primeira.amount()).isEqualByComparingTo("187.45");
        assertThat(primeira.description()).isEqualTo("COMPRA CARTAO MERCADO EXEMPLO");
        assertThat(primeira.externalId()).isEqualTo("FIT0001");
        assertThat(r.rows().get(2).amount()).isEqualByComparingTo("6500.00");   // "6.500,00"
        assertThat(r.rows().get(2).description()).isEqualTo("CRÉDITO SALÁRIO EMPRESA EXEMPLO");   // Windows-1252
    }

    @Test
    void leOBancoBComDebitoECreditoSeparadosEBomEAspas() {
        CsvSuggestion s = parser.suggest(Fixtures.bytes("banco-b.csv"));
        ParseResult r = parser.parse(Fixtures.bytes("banco-b.csv"), CsvMapping.fromMap(s.mapping()));

        assertThat(r.rows()).hasSize(12);
        assertThat(r.invalidCount()).isZero();
        assertThat(r.rows().get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(r.rows().get(2).type()).isEqualTo(TransactionType.INCOME);
        assertThat(r.rows().get(2).amount()).isEqualByComparingTo("6500.00");
        // campo com vírgula dentro de aspas ("Nome, Descrição") não quebra a coluna
        assertThat(r.rows().get(0).description()).contains(",").startsWith("Mercado Exemplo");   // dia par: "Nome, Memo" entre aspas
    }

    @Test
    void leOBancoCComTabulacaoTituloNoTopoETipoDebitoCredito() {
        CsvSuggestion s = parser.suggest(Fixtures.bytes("banco-c.csv"));
        assertThat(s.delimiter()).isEqualTo('\t');
        assertThat(s.skipLines()).isEqualTo(3);   // 3 linhas de título antes do cabeçalho

        ParseResult r = parser.parse(Fixtures.bytes("banco-c.csv"), CsvMapping.fromMap(s.mapping()));

        assertThat(r.rows()).hasSize(12);
        assertThat(r.invalidCount()).isZero();
        assertThat(r.rows().get(0).lineNo()).isEqualTo(5);   // 3 de título + cabeçalho + 1ª linha de dados
        assertThat(r.rows().get(0).type()).isEqualTo(TransactionType.EXPENSE);   // "D" com valor positivo
        assertThat(r.rows().get(0).amount()).isEqualByComparingTo("187.45");
        assertThat(r.rows().get(0).occurredAt()).isEqualTo(at("2026-03-02T15:00:00Z"));   // dd/MM/yy
        assertThat(r.rows().get(2).type()).isEqualTo(TransactionType.INCOME);             // "C"
    }

    @Test
    void osFormatosDeCsvEOOfxDaoOMesmoResultadoParaDataTipoEValor() {
        List<ParsedRow> referencia = ofx.parse(Fixtures.bytes("conta-corrente-1.ofx")).rows();
        for (String arquivo : List.of("banco-a.csv", "banco-b.csv", "banco-c.csv")) {
            byte[] bytes = Fixtures.bytes(arquivo);
            List<ParsedRow> csv = parser.parse(bytes, CsvMapping.fromMap(parser.suggest(bytes).mapping())).rows();
            assertThat(csv).as(arquivo).hasSameSizeAs(referencia);
            for (int i = 0; i < referencia.size(); i++) {
                assertThat(csv.get(i).occurredAt()).as(arquivo + " linha " + i).isEqualTo(referencia.get(i).occurredAt());
                assertThat(csv.get(i).type()).as(arquivo + " tipo " + i).isEqualTo(referencia.get(i).type());
                assertThat(csv.get(i).amount()).as(arquivo + " valor " + i).isEqualByComparingTo(referencia.get(i).amount());
            }
            BigDecimal liquido = csv.stream().map(r -> r.type() == TransactionType.INCOME ? r.amount() : r.amount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(liquido).as(arquivo).isEqualByComparingTo("4179.53");
        }
    }

    @Test
    void arquivoComLinhasRuinsDevolveCadaMotivoEAsBoasContinuam() {
        ParseResult r = parser.parse(Fixtures.bytes("banco-a-invalido.csv"),
                mapping("delimiter", ";", "dateColumn", "Data", "amountColumn", "Valor", "descriptionColumn", "Histórico"));

        assertThat(r.rows()).hasSize(10);
        assertThat(r.validCount()).isEqualTo(2);
        assertThat(r.rows().get(0).valid()).isTrue();
        assertThat(r.rows().get(0).amount()).isEqualByComparingTo("10.00");
        assertThat(r.rows().get(8).valid()).isTrue();
        assertThat(r.rows().get(8).amount()).isEqualByComparingTo("20.50");
        assertThat(r.rows().get(1).error()).isEqualTo("data inválida");              // 31/02
        assertThat(r.rows().get(2).error()).contains("2 casas");                       // -1,005
        assertThat(r.rows().get(3).error()).isEqualTo("valor ausente");
        assertThat(r.rows().get(4).error()).isEqualTo("valor inválido");              // abc
        assertThat(r.rows().get(5).error()).isEqualTo("valor zero");
        assertThat(r.rows().get(6).error()).isEqualTo("data anterior a 2000");
        assertThat(r.rows().get(7).error()).isEqualTo("data no futuro");
        assertThat(r.rows().get(9).error()).isEqualTo("data ausente");
        assertThat(r.rows().get(1).lineNo()).isEqualTo(3);   // o número da linha aponta onde corrigir no arquivo
    }

    // ---------------------------------------------------------------- sugestão

    @Test
    void sugereMapeamentoParaOBancoA() {
        CsvSuggestion s = parser.suggest(Fixtures.bytes("banco-a.csv"));

        assertThat(s.delimiter()).isEqualTo(';');
        assertThat(s.hasHeader()).isTrue();
        assertThat(s.skipLines()).isZero();
        assertThat(s.headers()).containsExactly("Data", "Histórico", "Documento", "Valor");
        assertThat(s.mapping()).containsEntry("dateColumn", "Data").containsEntry("dateFormat", "DMY_SLASH")
                .containsEntry("amountColumn", "Valor").containsEntry("descriptionColumn", "Histórico").containsEntry("idColumn", "Documento");
        assertThat(s.sample()).hasSize(5);
        assertThat(s.sample().get(0)).startsWith("02/03/2026", "COMPRA CARTAO MERCADO EXEMPLO");
    }

    @Test
    void sugereDebitoECreditoParaOBancoBEFormatoIso() {
        CsvSuggestion s = parser.suggest(Fixtures.bytes("banco-b.csv"));

        assertThat(s.delimiter()).isEqualTo(',');
        assertThat(s.mapping()).containsEntry("dateColumn", "Date").containsEntry("dateFormat", "YMD_DASH")
                .containsEntry("debitColumn", "Debit").containsEntry("creditColumn", "Credit")
                .containsEntry("descriptionColumn", "Description").doesNotContainKey("amountColumn");
    }

    @Test
    void sugereTipoETabulacaoParaOBancoC() {
        CsvSuggestion s = parser.suggest(Fixtures.bytes("banco-c.csv"));

        assertThat(s.mapping()).containsEntry("delimiter", "TAB").containsEntry("skipLines", "3").containsEntry("dateFormat", "DMY_SLASH_SHORT")
                .containsEntry("typeColumn", "Tipo").containsEntry("amountColumn", "Valor").containsEntry("descriptionColumn", "Lançamento");
    }

    @Test
    void semCabecalhoReconhecivelNaoInventaMapeamento() {
        CsvSuggestion s = parser.suggest("02/03/2026;MERCADO;-10,00\n03/03/2026;CAFE;-5,00\n".getBytes(StandardCharsets.UTF_8));
        assertThat(s.hasHeader()).isFalse();
        assertThat(s.mapping()).isEmpty();
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void cabecalhoSemPalavrasConhecidasNaoGeraPalpite() {
        CsvSuggestion s = parser.suggest("alfa;beta;gama\n1;2;3\n".getBytes(StandardCharsets.UTF_8));
        assertThat(s.hasHeader()).isTrue();
        assertThat(s.mapping()).isEmpty();
        assertThat(s.warnings()).anyMatch(w -> w.contains("adivinhar"));
    }

    @Test
    void colunaDeSaldoNaoEhConfundidaComOValor() {
        CsvSuggestion s = parser.suggest("Data;Descrição;Saldo;Valor\n02/03/2026;x;1.000,00;-10,00\n".getBytes(StandardCharsets.UTF_8));
        assertThat(s.mapping()).containsEntry("amountColumn", "Valor");
    }

    @Test
    void cabecalhoRepetidoUsaAPosicao() {
        CsvSuggestion s = parser.suggest("Data;Valor;Valor\n02/03/2026;-10,00;-5,00\n".getBytes(StandardCharsets.UTF_8));
        assertThat(s.mapping().get("amountColumn")).isEqualTo("#2");
    }

    // ---------------------------------------------------------------- tokenização

    @Test
    void aspasComDelimitadorQuebraDeLinhaEAspasEscapadas() {
        String csv = "Data;Descrição;Valor\n" + "02/03/2026;\"Loja; \"\"Central\"\"\nsegunda linha\";-10,00\n" + "03/03/2026;Simples;-5,00\n";
        ParseResult r = parse(csv, SIMPLE);

        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).description()).isEqualTo("Loja; \"Central\" segunda linha");
        assertThat(r.rows().get(0).lineNo()).isEqualTo(2);
        assertThat(r.rows().get(1).lineNo()).isEqualTo(4);   // a quebra dentro das aspas conta como linha física
    }

    @Test
    void aceitaCrlfLfECrIsoladoEUltimaLinhaSemQuebra() {
        for (String nl : new String[] {"\r\n", "\n", "\r"}) {
            ParseResult r = parse("Data;Descrição;Valor" + nl + "02/03/2026;a;-1,00" + nl + "03/03/2026;b;-2,00", SIMPLE);
            assertThat(r.rows()).as(nl.replace("\r", "CR").replace("\n", "LF")).hasSize(2);
            assertThat(r.rows().get(1).amount()).isEqualByComparingTo("2.00");
        }
    }

    @Test
    void ignoraLinhasEmBrancoEEspacosAoRedorDosCampos() {
        ParseResult r = parse("Data;Descrição;Valor\n\n  02/03/2026 ;  a  ; -1,00 \n   \n;;\n03/03/2026;b;-2,00\n\n", SIMPLE);
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).description()).isEqualTo("a");
    }

    @Test
    void linhaMaisCurtaQueOCabecalhoViraInvalidaSemQuebrarOArquivo() {
        ParseResult r = parse("Data;Descrição;Valor\n02/03/2026;a\n03/03/2026;b;-2,00\n", SIMPLE);
        assertThat(r.rows().get(0).error()).isEqualTo("valor ausente");
        assertThat(r.rows().get(1).valid()).isTrue();
    }

    @Test
    void aspasNaoFechadasRecusamOArquivo() {
        assertThatThrownBy(() -> parse("Data;Descrição;Valor\n02/03/2026;\"sem fechar;-1,00\n", SIMPLE))
                .isInstanceOfSatisfying(ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNCLOSED_QUOTE));
    }

    @Test
    void camposGigantesSaoCortadosENaoEstouramAMemoria() {
        ParseResult r = parse("Data;Descrição;Valor\n02/03/2026;" + "x".repeat(4_000_000) + ";-1,00\n", SIMPLE);
        assertThat(r.rows().get(0).valid()).isTrue();
        assertThat(r.rows().get(0).description()).hasSize(200);
    }

    @Test
    void detectaODelimitadorQuandoOMapeamentoNaoInforma() {
        for (String delim : new String[] {";", ",", "\t", "|"}) {
            String csv = String.join(delim, "Data", "Descrição", "Valor") + "\n" + String.join(delim, "02/03/2026", "a", "-1.00") + "\n"
                    + String.join(delim, "03/03/2026", "b", "-2.50") + "\n";
            CsvMapping m = mapping("dateColumn", "Data", "amountColumn", "Valor", "descriptionColumn", "Descrição");
            ParseResult r = parse(csv, m);
            assertThat(r.rows()).as("delimitador %s", delim.equals("\t") ? "TAB" : delim).hasSize(2);
            assertThat(r.rows().get(1).amount()).isEqualByComparingTo("2.50");
        }
    }

    @Test
    void usaOCharsetCertoComBomUtf8Windows1252EUtf8() {
        String csv = "Data;Descrição;Valor\n02/03/2026;AÇAÍ CAFÉ;-1,00\n";
        for (Charset cs : new Charset[] {StandardCharsets.UTF_8, Charset.forName("windows-1252")}) {
            assertThat(parser.parse(csv.getBytes(cs), SIMPLE).rows().get(0).description()).as(cs.name()).isEqualTo("AÇAÍ CAFÉ");
        }
        byte[] utf8 = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[utf8.length + 3];
        System.arraycopy(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, 0, bom, 0, 3);
        System.arraycopy(utf8, 0, bom, 3, utf8.length);
        ParseResult r = parser.parse(bom, SIMPLE);
        assertThat(r.rows().get(0).description()).isEqualTo("AÇAÍ CAFÉ");   // o BOM não vira parte do nome da 1ª coluna
    }

    // ---------------------------------------------------------------- formas de ler o valor

    @Test
    void invertSignTrocaDespesaPorReceitaParaFaturaDeCartaoQueExportaPositivo() {
        CsvMapping m = mapping("delimiter", ";", "dateColumn", "Data", "amountColumn", "Valor", "invertSign", "true");
        ParseResult r = parse("Data;Valor\n02/03/2026;100,00\n03/03/2026;-30,00\n", m);
        assertThat(r.rows().get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(r.rows().get(1).type()).isEqualTo(TransactionType.INCOME);
    }

    @Test
    void tipoDebitoCreditoComVariosRotulos() {
        CsvMapping m = mapping("delimiter", ";", "dateColumn", "Data", "amountColumn", "Valor", "typeColumn", "Tipo");
        ParseResult r = parse("Data;Tipo;Valor\n02/03/2026;D;10,00\n02/03/2026;Débito;10,00\n02/03/2026;SAÍDA;10,00\n"
                + "02/03/2026;C;10,00\n02/03/2026;crédito;10,00\n02/03/2026;Entrada;10,00\n02/03/2026;X;10,00\n02/03/2026;;10,00\n", m);
        assertThat(r.rows().subList(0, 3)).allSatisfy(row -> assertThat(row.type()).isEqualTo(TransactionType.EXPENSE));
        assertThat(r.rows().subList(3, 6)).allSatisfy(row -> assertThat(row.type()).isEqualTo(TransactionType.INCOME));
        assertThat(r.rows().get(6).error()).contains("tipo desconhecido");
        assertThat(r.rows().get(7).error()).contains("tipo desconhecido");
    }

    @Test
    void debitoECreditoNoMesmoLancamentoEOsDoisVaziosSaoInvalidos() {
        CsvMapping m = mapping("delimiter", ";", "dateColumn", "Data", "debitColumn", "Saída", "creditColumn", "Entrada");
        ParseResult r = parse("Data;Saída;Entrada\n02/03/2026;10,00;\n02/03/2026;;20,00\n02/03/2026;5,00;6,00\n02/03/2026;;\n02/03/2026;0,00;0,00\n", m);
        assertThat(r.rows().get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(r.rows().get(1).type()).isEqualTo(TransactionType.INCOME);
        assertThat(r.rows().get(2).error()).contains("débito e crédito");
        assertThat(r.rows().get(3).error()).isEqualTo("valor ausente");
        assertThat(r.rows().get(4).error()).isEqualTo("valor ausente");   // zeros nas duas colunas = nada lançado
    }

    @Test
    void semCabecalhoUsaPosicoes() {
        CsvMapping m = mapping("delimiter", ";", "hasHeader", "false", "dateColumn", "#1", "descriptionColumn", "#2", "amountColumn", "#3");
        ParseResult r = parse("02/03/2026;MERCADO;-10,00\n03/03/2026;CAFE;-5,00\n", m);
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).lineNo()).isEqualTo(1);
        assertThat(r.rows().get(1).description()).isEqualTo("CAFE");
    }

    @Test
    void semDescricaoAvisaQueADeduplicacaoUsaSoDataEValor() {
        CsvMapping m = mapping("delimiter", ";", "dateColumn", "Data", "amountColumn", "Valor");
        ParseResult r = parse("Data;Valor\n02/03/2026;-10,00\n", m);
        assertThat(r.rows().get(0).description()).isNull();
        assertThat(r.warnings()).anyMatch(w -> w.contains("descrição"));
    }

    @Test
    void identificadorEEstabelecimentoOpcionais() {
        CsvMapping m = mapping("delimiter", ";", "dateColumn", "Data", "amountColumn", "Valor", "idColumn", "Doc", "merchantColumn", "Loja");
        ParsedRow row = parse("Data;Valor;Doc;Loja\n02/03/2026;-10,00;  ABC123  ;Loja Exemplo\n", m).rows().get(0);
        assertThat(row.externalId()).isEqualTo("ABC123");
        assertThat(row.merchant()).isEqualTo("Loja Exemplo");
    }

    // ---------------------------------------------------------------- mapeamento recusado

    @Test
    void colunaInexistenteRecusaOArquivoEListaAsDisponiveis() {
        CsvMapping m = mapping("delimiter", ";", "dateColumn", "Quando", "amountColumn", "Valor");
        assertThatThrownBy(() -> parse("Data;Valor\n02/03/2026;-1,00\n", m)).isInstanceOfSatisfying(ImportParseException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.BAD_MAPPING);
            assertThat(e.getMessage()).contains("Quando").contains("Data, Valor");
        });
        CsvMapping posicao = mapping("delimiter", ";", "dateColumn", "#9", "amountColumn", "#2");
        assertThatThrownBy(() -> parse("Data;Valor\n02/03/2026;-1,00\n", posicao)).isInstanceOf(ImportParseException.class);
    }

    @Test
    void acentoECaixaNaoImportamNoNomeDaColuna() {
        CsvMapping m = mapping("delimiter", ";", "dateColumn", "DATA", "amountColumn", "valor", "descriptionColumn", "descricao");
        assertThat(parse("Data;Descrição;Valor\n02/03/2026;x;-1,00\n", m).rows().get(0).valid()).isTrue();
    }

    @Test
    void recusaArquivoSoComCabecalhoVazioEExcessoDeLinhas() {
        assertThatThrownBy(() -> parse("Data;Descrição;Valor\n", SIMPLE)).isInstanceOfSatisfying(ImportParseException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NO_TRANSACTIONS));
        assertThatThrownBy(() -> parser.parse(new byte[0], SIMPLE)).isInstanceOfSatisfying(ImportParseException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.EMPTY));
        String linha = "02/03/2026;a;-1,00\n";
        assertThat(parse("Data;Descrição;Valor\n" + linha.repeat(ImportLimits.MAX_ROWS), SIMPLE).rows()).hasSize(ImportLimits.MAX_ROWS);
        assertThatThrownBy(() -> parse("Data;Descrição;Valor\n" + linha.repeat(ImportLimits.MAX_ROWS + 1), SIMPLE)).isInstanceOfSatisfying(
                ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_ROWS));
    }

    @Test
    void recusaBinario() {
        assertThatThrownBy(() -> parser.parse(new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, 1, 2}, SIMPLE))
                .isInstanceOfSatisfying(ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_TEXT));
    }
}
