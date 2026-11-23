package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.imports.parse.ImportParseException.Reason;
import dev.contaforte.transactions.TransactionType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfxParserTest {

    private final OfxParser parser = new OfxParser(Fixtures.CLOCK);

    // ---------------------------------------------------------------- montagem de arquivos de teste

    private static String envelope(String curdef, String acct, String... trns) {
        return "OFXHEADER:100\r\nDATA:OFXSGML\r\nVERSION:102\r\nCHARSET:NONE\r\n\r\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>"
                + (curdef == null ? "" : "<CURDEF>" + curdef) + "<BANKACCTFROM><ACCTID>" + acct + "</BANKACCTFROM><BANKTRANLIST>"
                + String.join("", trns) + "</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";
    }

    private static String trn(String date, String amount, String fitid, String memo) {
        return "<STMTTRN><TRNTYPE>OTHER<DTPOSTED>" + date + "<TRNAMT>" + amount
                + (fitid == null ? "" : "<FITID>" + fitid) + (memo == null ? "" : "<MEMO>" + memo) + "</STMTTRN>";
    }

    private ParseResult parse(String text) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private ParsedRow one(String trn) {
        ParseResult r = parse(envelope("BRL", "1", trn));
        assertThat(r.rows()).hasSize(1);
        return r.rows().get(0);
    }

    // ---------------------------------------------------------------- fixtures versionadas

    @Test
    void leOOfx1SgmlEmWindows1252ComAcentosCorretos() {
        ParseResult r = parser.parse(Fixtures.bytes("conta-corrente-1.ofx"));

        assertThat(r.rows()).hasSize(12);
        assertThat(r.invalidCount()).isZero();
        assertThat(r.warnings()).isEmpty();
        ParsedRow primeira = r.rows().get(0);
        assertThat(primeira.lineNo()).isEqualTo(1);
        assertThat(primeira.occurredAt()).isEqualTo(Instant.parse("2026-03-02T15:00:00Z"));   // 12:00 em UTC-3
        assertThat(primeira.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(primeira.amount()).isEqualByComparingTo("187.45");
        assertThat(primeira.description()).isEqualTo("COMPRA CARTAO MERCADO EXEMPLO");
        assertThat(primeira.merchant()).isEqualTo("Mercado Exemplo");
        assertThat(primeira.externalId()).isEqualTo("FIT0001");

        ParsedRow salario = r.rows().get(2);
        assertThat(salario.type()).isEqualTo(TransactionType.INCOME);
        assertThat(salario.amount()).isEqualByComparingTo("6500.00");
        assertThat(salario.description()).isEqualTo("CRÉDITO SALÁRIO EMPRESA EXEMPLO");   // cp1252 decodificado
        assertThat(r.rows().get(7).description()).isEqualTo("PIX RECEBIDO JOANA EXEMPLO");
        // as duas padarias idênticas continuam duas linhas, com FITIDs diferentes
        assertThat(r.rows().get(5).description()).isEqualTo(r.rows().get(6).description());
        assertThat(r.rows().get(5).externalId()).isEqualTo("FIT0006");
        assertThat(r.rows().get(6).externalId()).isEqualTo("FIT0007");
    }

    @Test
    void leOOfx2XmlEmUtf8ComDataSoComODia() {
        ParseResult r = parser.parse(Fixtures.bytes("conta-corrente-2.ofx"));

        assertThat(r.rows()).hasSize(7);   // 4 repetidas do primeiro arquivo + 3 novas
        assertThat(r.rows().get(0).externalId()).isEqualTo("FIT0009");
        assertThat(r.rows().get(0).description()).isEqualTo("DÉBITO AUTOMÁTICO ENERGIA EXEMPLO");
        assertThat(r.rows().get(0).occurredAt()).isEqualTo(Instant.parse("2026-03-15T15:00:00Z"));   // só o dia: meio-dia de SP
        assertThat(r.rows().get(6).type()).isEqualTo(TransactionType.INCOME);                        // estorno
        assertThat(r.rows().get(6).amount()).isEqualByComparingTo("64.99");
    }

    @Test
    void leFaturaDeCartaoComComprasNegativasEPagamentoPositivo() {
        ParseResult r = parser.parse(Fixtures.bytes("fatura-cartao.ofx"));

        assertThat(r.rows()).hasSize(5);
        assertThat(r.rows().subList(0, 4)).allSatisfy(row -> assertThat(row.type()).isEqualTo(TransactionType.EXPENSE));
        assertThat(r.rows().get(4).type()).isEqualTo(TransactionType.INCOME);
        assertThat(r.rows().get(4).amount()).isEqualByComparingTo("1200.00");
    }

    @Test
    void recusaOfxComMoedaDiferenteDeReais() {
        assertThatThrownBy(() -> parser.parse(Fixtures.bytes("moeda-usd.ofx")))
                .isInstanceOfSatisfying(ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNSUPPORTED_CURRENCY));
    }

    @Test
    void recusaOfxComMaisDeUmaConta() {
        assertThatThrownBy(() -> parser.parse(Fixtures.bytes("duas-contas.ofx")))
                .isInstanceOfSatisfying(ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MULTIPLE_ACCOUNTS));
    }

    @Test
    void recusaOfxSemTransacoes() {
        assertThatThrownBy(() -> parser.parse(Fixtures.bytes("sem-transacoes.ofx")))
                .isInstanceOfSatisfying(ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NO_TRANSACTIONS));
    }

    // ---------------------------------------------------------------- datas

    @Test
    void interpretaOsFormatosDeDataDoOfx() {
        assertThat(one(trn("20260310", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-10T15:00:00Z"));            // só dia: 12:00 SP
        assertThat(one(trn("20260310083000", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-10T11:30:00Z"));     // sem fuso: horário de SP
        assertThat(one(trn("20260310083000.000[0:GMT]", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-10T08:30:00Z"));
        assertThat(one(trn("20260310083000[-3:BRT]", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-10T11:30:00Z"));
        assertThat(one(trn("20260310083000[-03.00:BRT]", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-10T11:30:00Z"));
        assertThat(one(trn("20260310083000[+5.5:IST]", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-10T03:00:00Z"));
        assertThat(one(trn("202603100830", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-10T11:30:00Z"));          // HHMM
    }

    @Test
    void dataSemHoraNaoPulaODiaPorCausaDoFuso() {
        // 23h de 31/03 em SP já é 01/04 em UTC; já o meio-dia de 31/03 em SP continua em 31/03 em UTC
        assertThat(one(trn("20260331", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-03-31T15:00:00Z"));
        assertThat(one(trn("20260401000000[-3:BRT]", "-1.00", "A", "x")).occurredAt()).isEqualTo(Instant.parse("2026-04-01T03:00:00Z"));
    }

    @Test
    void usaDtuserQuandoNaoHaDtposted() {
        ParseResult r = parse(envelope("BRL", "1", "<STMTTRN><DTUSER>20260310<TRNAMT>-5.00<FITID>A</STMTTRN>"));
        assertThat(r.rows().get(0).valid()).isTrue();
        assertThat(r.rows().get(0).occurredAt()).isEqualTo(Instant.parse("2026-03-10T15:00:00Z"));
    }

    @Test
    void linhasComDataRuimViramInvalidasComMotivoEOArquivoSegue() {
        ParseResult r = parse(envelope("BRL", "1",
                trn("20260310", "-1.00", "OK", "ok"),
                trn("20261340", "-1.00", "B1", "mês 13"),
                trn("20260230", "-1.00", "B2", "30 de fevereiro"),
                trn("2026031", "-1.00", "B3", "curta"),
                trn("abcdefgh", "-1.00", "B4", "texto"),
                trn("19991231", "-1.00", "B5", "antes de 2000"),
                trn("20991231", "-1.00", "B6", "futuro"),
                "<STMTTRN><TRNAMT>-1.00<FITID>B7</STMTTRN>"));

        assertThat(r.rows()).hasSize(8);
        assertThat(r.validCount()).isEqualTo(1);
        assertThat(r.rows().stream().skip(1).map(ParsedRow::error)).containsExactly(
                "data inválida", "data inválida", "data inválida", "data inválida", "data anterior a 2000", "data no futuro", "data ausente");
    }

    // ---------------------------------------------------------------- valores e campos

    @Test
    void sinalDefineOTipoEZeroEhInvalido() {
        assertThat(one(trn("20260310", "-50.25", "A", "x")).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(one(trn("20260310", "50.25", "A", "x")).type()).isEqualTo(TransactionType.INCOME);
        assertThat(one(trn("20260310", "+50.25", "A", "x")).type()).isEqualTo(TransactionType.INCOME);
        assertThat(one(trn("20260310", "-50.25", "A", "x")).amount()).isEqualByComparingTo("50.25");   // valor sempre positivo
        assertThat(one(trn("20260310", "0.00", "A", "x")).error()).isEqualTo("valor zero");
        assertThat(one(trn("20260310", "-0", "A", "x")).error()).isEqualTo("valor zero");
    }

    @Test
    void aceitaVirgulaDecimalDeAlgunsBancosEMilharMisturado() {
        assertThat(one(trn("20260310", "-12,50", "A", "x")).amount()).isEqualByComparingTo("12.50");
        assertThat(one(trn("20260310", "-1.234,56", "A", "x")).amount()).isEqualByComparingTo("1234.56");
        assertThat(one(trn("20260310", "-1,234.56", "A", "x")).amount()).isEqualByComparingTo("1234.56");
    }

    @Test
    void valoresRuinsViramInvalidosSemArredondar() {
        assertThat(one(trn("20260310", "-1.005", "A", "x")).error()).contains("2 casas");
        assertThat(one(trn("20260310", "abc", "A", "x")).error()).isEqualTo("valor inválido");
        assertThat(one("<STMTTRN><DTPOSTED>20260310<FITID>A</STMTTRN>").error()).isEqualTo("valor ausente");
        assertThat(one(trn("20260310", "-9999999999999.00", "A", "x")).error()).isEqualTo("valor acima do limite");
    }

    @Test
    void nomeEMemoViramEstabelecimentoEDescricao() {
        ParsedRow ambos = one("<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00<NAME>Loja Exemplo<MEMO>COMPRA CARTAO LOJA</STMTTRN>");
        assertThat(ambos.description()).isEqualTo("COMPRA CARTAO LOJA");
        assertThat(ambos.merchant()).isEqualTo("Loja Exemplo");

        ParsedRow soNome = one("<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00<NAME>Loja Exemplo</STMTTRN>");
        assertThat(soNome.description()).isEqualTo("Loja Exemplo");
        assertThat(soNome.merchant()).isNull();

        ParsedRow iguais = one("<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00<NAME>loja exemplo<MEMO>LOJA EXEMPLO</STMTTRN>");
        assertThat(iguais.merchant()).isNull();   // não repete a mesma coisa

        ParsedRow nenhum = one("<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00</STMTTRN>");
        assertThat(nenhum.valid()).isTrue();
        assertThat(nenhum.description()).isNull();
    }

    @Test
    void semFitidOIdentificadorFicaNuloEFitidLongoEhCortado() {
        assertThat(one(trn("20260310", "-1.00", null, "x")).externalId()).isNull();
        assertThat(one(trn("20260310", "-1.00", "   ", "x")).externalId()).isNull();
        assertThat(one(trn("20260310", "-1.00", "F".repeat(400), "x")).externalId()).hasSize(255);
    }

    @Test
    void descricaoLongaEhCortadaEControlesSaem() {
        ParsedRow r = one(trn("20260310", "-1.00", "A", "A".repeat(500) + "\u0007"));
        assertThat(r.description()).hasSize(200);
        assertThat(one(trn("20260310", "-1.00", "A", "a   \t b")).description()).isEqualTo("a b");
    }

    @Test
    void decodificaEntidadesXmlEhNumericasMasNadaAlemDisso() {
        ParsedRow r = one(trn("20260310", "-1.00", "A", "Bar &amp; Grill &lt;3 &#233;&#xE9; &quot;x&quot; &apos;y&apos;"));
        assertThat(r.description()).isEqualTo("Bar & Grill <3 éé \"x\" 'y'");
        assertThat(one(trn("20260310", "-1.00", "A", "&desconhecida; &#99999999; &#;")).description()).isEqualTo("&desconhecida; &#99999999; &#;");
    }

    // ---------------------------------------------------------------- estrutura e tolerância

    @Test
    void sgmlSemTagDeFechamentoDoStmttrnSeparaAsTransacoes() {
        String text = "<OFX><CURDEF>BRL<ACCTID>1<STMTTRN><DTPOSTED>20260301<TRNAMT>-1.00<FITID>A"
                + "<STMTTRN><DTPOSTED>20260302<TRNAMT>-2.00<FITID>B<STMTTRN><DTPOSTED>20260303<TRNAMT>-3.00<FITID>C</OFX>";
        ParseResult r = parse(text);
        assertThat(r.rows()).extracting(ParsedRow::externalId).containsExactly("A", "B", "C");
        assertThat(r.rows()).extracting(ParsedRow::lineNo).containsExactly(1, 2, 3);
    }

    @Test
    void aceitaTagsEmMinusculasAtributosEElementosVazios() {
        String text = "<ofx><curdef>BRL</curdef><acctid>1</acctid><stmttrn id=\"7\"><dtposted>20260310</dtposted>"
                + "<trnamt>-4.00</trnamt><fitid>x1</fitid><memo/><name>Teste</name></stmttrn></ofx>";
        ParsedRow r = parse(text).rows().get(0);
        assertThat(r.valid()).isTrue();
        assertThat(r.description()).isEqualTo("Teste");
        assertThat(r.externalId()).isEqualTo("x1");
    }

    @Test
    void ignoraLixoAntesDoOfxEMarcasXml() {
        String text = "lixo qualquer\r\n<?xml version=\"1.0\"?>\r\n<!-- comentário -->\r\n" + envelope("BRL", "1", trn("20260310", "-1.00", "A", "x"));
        assertThat(parse(text).rows()).hasSize(1);
    }

    @Test
    void menorQueSoltoNoTextoNaoQuebraALeitura() {
        ParsedRow r = one(trn("20260310", "-1.00", "A", "valor < limite e > outro"));
        assertThat(r.valid()).isTrue();
        assertThat(r.description()).startsWith("valor");
    }

    @Test
    void semCurdefAvisaEAssumeReais() {
        ParseResult r = parse(envelope(null, "1", trn("20260310", "-1.00", "A", "x")));
        assertThat(r.rows()).hasSize(1);
        assertThat(r.warnings()).singleElement().asString().contains("moeda");
    }

    @Test
    void moedaEmMinusculasEhReconhecida() {
        assertThat(parse(envelope("brl", "1", trn("20260310", "-1.00", "A", "x"))).rows()).hasSize(1);
        assertThatThrownBy(() -> parse(envelope("eur", "1", trn("20260310", "-1.00", "A", "x")))).isInstanceOf(ImportParseException.class);
    }

    @Test
    void contaRepetidaNaoContaComoDuasContas() {
        String text = "<OFX><CURDEF>BRL<ACCTID>1<STMTTRN><DTPOSTED>20260301<TRNAMT>-1.00</STMTTRN><ACCTID>1<STMTTRN><DTPOSTED>20260302<TRNAMT>-2.00</STMTTRN></OFX>";
        assertThat(parse(text).rows()).hasSize(2);
    }

    // ---------------------------------------------------------------- codificação

    @Test
    void respeitaOCharsetDoCabecalho() {
        byte[] latin = ("OFXHEADER:100\r\nCHARSET:1252\r\n\r\n<OFX><CURDEF>BRL<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00<MEMO>CAFÉ</STMTTRN></OFX>")
                .getBytes(java.nio.charset.Charset.forName("windows-1252"));
        assertThat(parser.parse(latin).rows().get(0).description()).isEqualTo("CAFÉ");
    }

    @Test
    void semCharsetDeclaradoDetectaUtf8OuCaiParaWindows1252() {
        String corpo = "<OFX><CURDEF>BRL<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00<MEMO>CAFÉ DA MANHÃ</STMTTRN></OFX>";
        assertThat(parser.parse(corpo.getBytes(StandardCharsets.UTF_8)).rows().get(0).description()).isEqualTo("CAFÉ DA MANHÃ");
        assertThat(parser.parse(corpo.getBytes(java.nio.charset.Charset.forName("windows-1252"))).rows().get(0).description()).isEqualTo("CAFÉ DA MANHÃ");
    }

    @Test
    void aceitaBomUtf8EUtf16() {
        String corpo = "<OFX><CURDEF>BRL<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00<MEMO>AÇAÍ</STMTTRN></OFX>";
        byte[] utf8 = corpo.getBytes(StandardCharsets.UTF_8);
        byte[] comBom = new byte[utf8.length + 3];
        System.arraycopy(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, 0, comBom, 0, 3);
        System.arraycopy(utf8, 0, comBom, 3, utf8.length);
        assertThat(parser.parse(comBom).rows().get(0).description()).isEqualTo("AÇAÍ");

        byte[] u16 = corpo.getBytes(StandardCharsets.UTF_16LE);
        byte[] comBom16 = new byte[u16.length + 2];
        comBom16[0] = (byte) 0xFF;
        comBom16[1] = (byte) 0xFE;
        System.arraycopy(u16, 0, comBom16, 2, u16.length);
        assertThat(parser.parse(comBom16).rows().get(0).description()).isEqualTo("AÇAÍ");
    }

    // ---------------------------------------------------------------- arquivo recusado inteiro

    @Test
    void recusaVazioNaoOfxBinarioETamanhoExcessivo() {
        assertThatThrownBy(() -> parser.parse(new byte[0])).isInstanceOfSatisfying(ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.EMPTY));
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(ImportParseException.class);
        assertThatThrownBy(() -> parse("isto é só um texto qualquer\nsem marca nenhuma")).isInstanceOfSatisfying(ImportParseException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.UNKNOWN_FORMAT));
        assertThatThrownBy(() -> parser.parse(new byte[] {'P', 'K', 3, 4, 1, 2, 3})).isInstanceOfSatisfying(ImportParseException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_TEXT));
        assertThatThrownBy(() -> parser.parse("<OFX>\0\0\0".getBytes(StandardCharsets.UTF_8))).isInstanceOfSatisfying(ImportParseException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_TEXT));
        assertThatThrownBy(() -> parser.parse(new byte[ImportLimits.MAX_BYTES + 1])).isInstanceOfSatisfying(ImportParseException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void aceitaExatamenteOLimiteDeLinhasERecusaUmaAMais() {
        String um = trn("20260310", "-1.00", null, null);
        assertThat(parse(envelope("BRL", "1", um.repeat(ImportLimits.MAX_ROWS))).rows()).hasSize(ImportLimits.MAX_ROWS);
        assertThatThrownBy(() -> parse(envelope("BRL", "1", um.repeat(ImportLimits.MAX_ROWS + 1)))).isInstanceOfSatisfying(
                ImportParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_ROWS));
    }

    @Test
    void naoMexeEmEntidadesExternasNemExpandeEntidadesAninhadas() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE ofx [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + envelope("BRL", "1", trn("20260310", "-1.00", "A", "&xxe;"));
        ParsedRow r = parse(xxe).rows().get(0);
        assertThat(r.description()).isEqualTo("&xxe;");        // continua texto literal
        assertThat(r.description()).doesNotContain("root:");

        String laughs = "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">]>"
                + envelope("BRL", "1", trn("20260310", "-1.00", "A", "&lol3;"));
        assertThat(parse(laughs).rows().get(0).description()).isEqualTo("&lol3;");
    }
}
