package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Entrada hostil ou quebrada nunca pode derrubar o backend: ou vem um resultado, ou vem {@link ImportParseException}.
 * Qualquer outra exceção (ou demorar demais) é falha. Semente fixa: reproduzível.
 */
class ParserRobustnessTest {

    private static final Duration LIMITE = Duration.ofSeconds(8);
    private final OfxParser ofx = new OfxParser(Fixtures.CLOCK);
    private final CsvParser csv = new CsvParser(Fixtures.CLOCK);
    private static final CsvMapping POSICOES = CsvMapping.fromMap(java.util.Map.of(
            "hasHeader", "false", "dateColumn", "#1", "amountColumn", "#2", "descriptionColumn", "#3"));
    private static final CsvMapping COM_CABECALHO = CsvMapping.fromMap(java.util.Map.of(
            "dateColumn", "Data", "amountColumn", "Valor", "descriptionColumn", "Descrição"));

    /** Roda os dois parsers e a sugestão: só ImportParseException é aceita. */
    private void tudo(byte[] input) {
        for (Runnable r : List.<Runnable>of(() -> ofx.parse(input), () -> csv.parse(input, POSICOES),
                () -> csv.parse(input, COM_CABECALHO), () -> csv.suggest(input), () -> ContentSniffer.detect(input))) {
            try {
                r.run();
            } catch (ImportParseException esperado) {
                // recusa controlada
            }
        }
    }

    private void rapido(String nome, byte[] input) {
        assertTimeoutPreemptively(LIMITE, () -> tudo(input), nome + " passou do tempo limite");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void bytesAleatoriosNuncaGeramExcecaoInesperada() {
        Random rnd = new Random(20260101);
        for (int i = 0; i < 3000; i++) {
            byte[] b = new byte[rnd.nextInt(4000)];
            rnd.nextBytes(b);
            tudo(b);
        }
    }

    @Test
    void textoAleatorioComCaracteresEspeciaisNuncaGeraExcecaoInesperada() {
        Random rnd = new Random(7);
        String alfabeto = "<>/&;\"',\t\n\r |#=ABCSTRNMOFXabc0123456789-+.[]():!?éç";
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(600);
            for (int j = 0; j < len; j++) sb.append(alfabeto.charAt(rnd.nextInt(alfabeto.length())));
            tudo(bytes(sb.toString()));
        }
    }

    @Test
    void arquivosVerdadeirosMutiladosNuncaGeramExcecaoInesperada() {
        Random rnd = new Random(42);
        for (String nome : List.of("conta-corrente-1.ofx", "conta-corrente-2.ofx", "fatura-cartao.ofx", "banco-a.csv", "banco-b.csv",
                "banco-c.csv", "banco-a-invalido.csv")) {
            byte[] original = Fixtures.bytes(nome);
            for (int i = 0; i < 400; i++) {
                byte[] b = original.clone();
                switch (rnd.nextInt(4)) {
                    case 0 -> {   // troca de bytes
                        for (int k = 0, n = 1 + rnd.nextInt(20); k < n; k++) b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
                    }
                    case 1 -> b = Arrays.copyOf(b, rnd.nextInt(b.length));   // truncamento
                    case 2 -> {   // pedaço repetido
                        int from = rnd.nextInt(b.length);
                        int len = Math.min(b.length - from, 1 + rnd.nextInt(200));
                        byte[] out = new byte[b.length + len];
                        System.arraycopy(b, 0, out, 0, from + len);
                        System.arraycopy(b, from, out, from + len, b.length - from);
                        b = out;
                    }
                    default -> {   // remoção de um pedaço
                        int from = rnd.nextInt(b.length);
                        int len = Math.min(b.length - from, 1 + rnd.nextInt(300));
                        byte[] out = new byte[b.length - len];
                        System.arraycopy(b, 0, out, 0, from);
                        System.arraycopy(b, from + len, out, from, b.length - from - len);
                        b = out;
                    }
                }
                tudo(b);
            }
        }
    }

    // ---------------------------------------------------------------- entradas patológicas: terminam rápido

    @Test
    void ofxMilhoesDeMenorQueSemFechar() {
        rapido("menor-que", bytes("<OFX>" + "<".repeat(2_000_000)));
    }

    @Test
    void ofxMilhoesDeTagsAbertasSemConteudo() {
        rapido("tags", bytes("<OFX>" + "<A>".repeat(1_500_000)));
    }

    @Test
    void ofxMuitasTransacoesAbertasSemFechar() {
        rapido("stmttrn aberto", bytes("<OFX><CURDEF>BRL" + "<STMTTRN>".repeat(300_000)));
    }

    @Test
    void ofxValorGiganteComEComercialSemPontoEVirgula() {
        rapido("entidades", bytes("<OFX><CURDEF>BRL><STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00<MEMO>" + "&".repeat(3_000_000)));
        rapido("entidades curtas", bytes("<OFX><STMTTRN><MEMO>" + "&#1;".repeat(500_000) + "</STMTTRN>"));
    }

    @Test
    void ofxLinhaDeQuatroMegabytesSemQuebra() {
        rapido("linha longa", bytes("<OFX><STMTTRN><MEMO>" + "x".repeat(4_000_000)));
    }

    @Test
    void ofxProfundamenteAninhado() {
        rapido("aninhado", bytes("<OFX>" + "<STMTRS>".repeat(200_000) + "<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00</STMTTRN>"));
    }

    @Test
    void ofxCabecalhoGigante() {
        rapido("cabecalho", bytes("OFXHEADER:100\n" + "CHARSET:".repeat(200_000) + "<OFX>"));
    }

    @Test
    void csvMilhaoDeAspas() {
        rapido("aspas", bytes("\"".repeat(1_000_000)));
        rapido("aspas duplas", bytes("\"\"".repeat(1_000_000) + "\n"));
    }

    @Test
    void csvLinhaComCemMilColunas() {
        rapido("colunas", bytes(";".repeat(100_000) + "\n" + ";".repeat(100_000)));
    }

    @Test
    void csvCampoDeQuatroMegabytesESoQuebrasDeLinha() {
        rapido("campo gigante", bytes("Data;Valor;Descrição\n02/03/2026;-1,00;" + "y".repeat(4_000_000)));
        rapido("so quebras", bytes("\n".repeat(4_000_000)));
        rapido("so crlf", bytes("\r\n".repeat(2_000_000)));
    }

    @Test
    void csvDelimitadorEmArquivoDeUmaLinhaSo() {
        rapido("uma linha", bytes("a,b;c\t".repeat(800_000)));
    }

    @Test
    void valoresELatasLongosNaoCausamRegexLento() {
        // 40 caracteres é o máximo aceito: nada com milhões de caracteres chega a ser analisado
        rapido("valor", bytes("02/03/2026;" + "1".repeat(1_000_000) + ";x\n"));
        rapido("valor com separadores", bytes("02/03/2026;" + "1.".repeat(500_000) + ";x\n"));
        rapido("sinais", bytes("02/03/2026;" + "-".repeat(500_000) + ";x\n"));
    }

    @Test
    void vinteMilLinhasValidasSaoLidasEmPoucosSegundos() {
        StringBuilder x = new StringBuilder("<OFX><CURDEF>BRL<ACCTID>1");
        StringBuilder c = new StringBuilder("Data;Valor;Descrição\n");
        for (int i = 0; i < ImportLimits.MAX_ROWS; i++) {
            x.append("<STMTTRN><DTPOSTED>2026031").append(i % 9).append("<TRNAMT>-").append(i % 900 + 1).append(".50<FITID>F").append(i)
                    .append("<MEMO>COMPRA NUMERO ").append(i).append("</STMTTRN>");
            c.append("0").append(i % 9 + 1).append("/03/2026;-").append(i % 900 + 1).append(",50;COMPRA NUMERO ").append(i).append("\n");
        }
        x.append("</OFX>");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(ofx.parse(bytes(x.toString())).validCount()).isEqualTo(ImportLimits.MAX_ROWS);
            assertThat(csv.parse(bytes(c.toString()), COM_CABECALHO).validCount()).isEqualTo(ImportLimits.MAX_ROWS);
        });
    }

    @Test
    void mensagensDeErroDeLinhaNuncaEcoamOConteudoDoExtrato() {
        String segredo = "SEGREDO123456";
        ParseResult o = ofx.parse(bytes("<OFX><CURDEF>BRL<ACCTID>1"
                + "<STMTTRN><DTPOSTED>" + segredo + "<TRNAMT>-1.00</STMTTRN>"
                + "<STMTTRN><DTPOSTED>20260310<TRNAMT>" + segredo + "</STMTTRN>"
                + "<STMTTRN><DTPOSTED>20260310<TRNAMT>-1." + segredo.replaceAll("\\D", "") + "</STMTTRN></OFX>"));
        ParseResult c = csv.parse(bytes("Data;Valor;Descrição\n" + segredo + ";-1,00;x\n02/03/2026;" + segredo + ";x\n02/03/2026;-1," + segredo.replaceAll("\\D", "") + ";x\n"),
                COM_CABECALHO);
        for (ParseResult r : List.of(o, c)) {
            assertThat(r.invalidCount()).isEqualTo(r.rows().size());
            assertThat(r.rows()).allSatisfy(row -> assertThat(row.error()).doesNotContain(segredo).doesNotContain("123456"));
        }
    }

    @Test
    void resultadoDeEntradaInesperadaNuncaVemNulo() {
        ParseResult r = ofx.parse(bytes("<OFX><CURDEF>BRL<STMTTRN><DTPOSTED>20260310<TRNAMT>-1.00</STMTTRN></OFX>"));
        assertThat(r.rows()).isNotNull();
        assertThat(r.warnings()).isNotNull();
    }
}
