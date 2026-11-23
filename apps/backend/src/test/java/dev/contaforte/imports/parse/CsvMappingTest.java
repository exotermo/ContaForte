package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.imports.parse.ImportParseException.Reason;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvMappingTest {

    private static Map<String, String> base() {
        Map<String, String> m = new HashMap<>();
        m.put("dateColumn", "Data");
        m.put("amountColumn", "Valor");
        return m;
    }

    private static void rejects(Map<String, String> m, String contains) {
        assertThatThrownBy(() -> CsvMapping.fromMap(m)).isInstanceOfSatisfying(ImportParseException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.BAD_MAPPING);
            assertThat(e.getMessage()).contains(contains);
        });
    }

    @Test
    void padroesQuandoSoOObrigatorioEInformado() {
        CsvMapping m = CsvMapping.fromMap(base());
        assertThat(m.delimiter()).isNull();
        assertThat(m.skipLines()).isZero();
        assertThat(m.hasHeader()).isTrue();
        assertThat(m.dateFormat()).isEqualTo(CsvDateFormat.AUTO);
        assertThat(m.invertSign()).isFalse();
    }

    @Test
    void ida_e_voltaPeloMapa() {
        Map<String, String> in = base();
        in.put("delimiter", "TAB");
        in.put("skipLines", "3");
        in.put("dateFormat", "dmy_slash_short");
        in.put("descriptionColumn", "Histórico");
        in.put("invertSign", "true");
        CsvMapping m = CsvMapping.fromMap(in);

        assertThat(m.delimiter()).isEqualTo('\t');
        assertThat(m.dateFormat()).isEqualTo(CsvDateFormat.DMY_SLASH_SHORT);
        assertThat(CsvMapping.fromMap(m.toMap())).isEqualTo(m);
    }

    @Test
    void campoDesconhecidoDelimitadorEFormatoInvalidosSaoRecusados() {
        Map<String, String> typo = base();
        typo.put("amountColum", "x");
        rejects(typo, "desconhecido");
        Map<String, String> delim = base();
        delim.put("delimiter", "::");
        rejects(delim, "Delimitador");
        Map<String, String> fmt = base();
        fmt.put("dateFormat", "yyyy-dd-MM");
        rejects(fmt, "Formato de data");
        Map<String, String> skip = base();
        skip.put("skipLines", "abc");
        rejects(skip, "skipLines");
        Map<String, String> skipBig = base();
        skipBig.put("skipLines", "101");
        rejects(skipBig, "skipLines");
        Map<String, String> longo = base();
        longo.put("descriptionColumn", "x".repeat(101));
        rejects(longo, "longo");
    }

    @Test
    void exigeDataEUmaFormaDeLerOValor() {
        Map<String, String> semData = new HashMap<>();
        semData.put("amountColumn", "Valor");
        rejects(semData, "data");
        Map<String, String> semValor = new HashMap<>();
        semValor.put("dateColumn", "Data");
        rejects(semValor, "valor");
        Map<String, String> duas = base();
        duas.put("debitColumn", "Saída");
        rejects(duas, "OU");
        Map<String, String> semHeader = base();
        semHeader.put("hasHeader", "false");
        rejects(semHeader, "posição");
        assertThatThrownBy(() -> CsvMapping.fromMap(null)).isInstanceOf(ImportParseException.class);
    }

    @Test
    void semCabecalhoAceitaSoPosicoes() {
        Map<String, String> m = new HashMap<>();
        m.put("hasHeader", "false");
        m.put("dateColumn", "#1");
        m.put("amountColumn", "#3");
        assertThat(CsvMapping.fromMap(m).hasHeader()).isFalse();
    }
}
