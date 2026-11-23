package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DateTimeException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CsvDateFormatTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "DMY_SLASH|10/03/2026|2026-03-10T15:00:00Z", "DMY_SLASH|1/3/2026|2026-03-01T15:00:00Z",
            "DMY_SLASH_SHORT|10/03/26|2026-03-10T15:00:00Z", "DMY_SLASH_SHORT|10/03/99|1999-03-10T15:00:00Z",
            "DMY_DASH|10-03-2026|2026-03-10T15:00:00Z", "DMY_DOT|10.03.2026|2026-03-10T15:00:00Z",
            "YMD_DASH|2026-03-10|2026-03-10T15:00:00Z", "YMD_SLASH|2026/03/10|2026-03-10T15:00:00Z",
            "AUTO|10/03/2026|2026-03-10T15:00:00Z", "AUTO|2026-03-10|2026-03-10T15:00:00Z", "AUTO|10.03.2026|2026-03-10T15:00:00Z",
            "AUTO|10-03-2026|2026-03-10T15:00:00Z", "AUTO|10/03/26|2026-03-10T15:00:00Z", "AUTO|2026/03/10|2026-03-10T15:00:00Z"})
    void leCadaFormatoComMeioDiaDeSaoPaulo(String format, String input, String expected) {
        assertThat(CsvDateFormat.valueOf(format).parse(input)).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "10/03/2026 14:30|2026-03-10T17:30:00Z", "10/03/2026 14:30:15|2026-03-10T17:30:15Z", "2026-03-10T14:30:00|2026-03-10T17:30:00Z",
            "2026-03-10 14:30|2026-03-10T17:30:00Z", "2026-03-10T14:30:00Z|2026-03-10T14:30:00Z",
            "2026-03-10T14:30:00-03:00|2026-03-10T17:30:00Z", "2026-03-10T14:30:00+02:00|2026-03-10T12:30:00Z",
            "2026-03-10T14:30:00.123Z|2026-03-10T14:30:00Z"})
    void aceitaHoraComFusoExplicitoOuNoHorarioDeSaoPaulo(String input, String expected) {
        assertThat(CsvDateFormat.AUTO.parse(input)).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31/04/2026", "29/02/2026", "00/01/2026", "10/13/2026", "32/01/2026", "10/03", "10/03/2026/1", "abc", "", "  ",
            "10//2026", "10/03/26a", "99999/03/10", "10/03/2026 25:00", "10/03/2026 14", "10/03/2026 14:60", "2026-03-10T14:30:00+99:00"})
    void recusaDatasImpossiveisOuMalformadas(String input) {
        assertThatThrownBy(() -> CsvDateFormat.AUTO.parse(input)).isInstanceOf(DateTimeException.class);
    }

    @Test
    void formatoExplicitoRecusaOutroFormato() {
        assertThatThrownBy(() -> CsvDateFormat.DMY_SLASH.parse("2026-03-10")).isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> CsvDateFormat.YMD_DASH.parse("10/03/2026")).isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> CsvDateFormat.DMY_SLASH.parse("10/03/26")).isInstanceOf(DateTimeException.class);       // ano de 2 dígitos
        assertThatThrownBy(() -> CsvDateFormat.DMY_SLASH_SHORT.parse("10/03/2026")).isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> CsvDateFormat.DMY_DASH.parse("10/03/2026")).isInstanceOf(DateTimeException.class);
    }

    @Test
    void barraEhSempreDiaMesNuncaMesDia() {
        // 03/10/2026 é 3 de outubro, não 10 de março
        assertThat(CsvDateFormat.AUTO.parse("03/10/2026")).isEqualTo(Instant.parse("2026-10-03T15:00:00Z"));
    }

    @Test
    void textoLongoDemaisEhRecusadoSemProcessar() {
        assertThatThrownBy(() -> CsvDateFormat.AUTO.parse("1".repeat(10_000))).isInstanceOf(DateTimeException.class);
    }
}
