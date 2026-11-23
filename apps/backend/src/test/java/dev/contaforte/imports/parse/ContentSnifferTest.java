package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.imports.ImportFormat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContentSnifferTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void reconheceOfxPeloConteudoMesmoComOutraExtensao() {
        assertThat(ContentSniffer.detect(Fixtures.bytes("conta-corrente-1.ofx"))).contains(ImportFormat.OFX);
        assertThat(ContentSniffer.detect(Fixtures.bytes("conta-corrente-2.ofx"))).contains(ImportFormat.OFX);
        assertThat(ContentSniffer.detect(b("qualquer coisa\n<ofx>\n<stmttrn>"))).contains(ImportFormat.OFX);
    }

    @Test
    void reconheceCsvDeVariosDelimitadores() {
        for (String f : new String[] {"banco-a.csv", "banco-b.csv", "banco-c.csv"}) {
            assertThat(ContentSniffer.detect(Fixtures.bytes(f))).as(f).contains(ImportFormat.CSV);
        }
        assertThat(ContentSniffer.detect(b("a|b|c\n1|2|3\n"))).contains(ImportFormat.CSV);
    }

    @Test
    void recusaBinariosETextoSemFormato() {
        assertThat(ContentSniffer.detect(new byte[] {'P', 'K', 3, 4, 0, 0})).isEmpty();
        assertThat(ContentSniffer.detect(new byte[] {'%', 'P', 'D', 'F', '-', '1'})).isEmpty();
        assertThat(ContentSniffer.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10})).isEmpty();
        assertThat(ContentSniffer.detect(new byte[] {'a', 0, 'b', 0})).isEmpty();
        assertThat(ContentSniffer.detect(b("só uma linha sem delimitador"))).isEmpty();
        assertThat(ContentSniffer.detect(b("duas linhas\nsem nada especial\n"))).isEmpty();
        assertThat(ContentSniffer.detect(new byte[0])).isEmpty();
        assertThat(ContentSniffer.detect(null)).isEmpty();
    }

    @Test
    void ofxVenceCsvQuandoOsDoisParecem() {
        assertThat(ContentSniffer.detect(b("OFXHEADER:100\n;;;\n<OFX>"))).contains(ImportFormat.OFX);
    }
}
