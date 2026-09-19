package cl.duoc.bancoxyz.batch.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FechaLegacyParserTest {

    @Test
    void deberia_interpretar_los_cuatro_formatos_legacy_conocidos() {
        assertThat(FechaLegacyParser.parseOrNull("2024-06-30")).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(FechaLegacyParser.parseOrNull("2024/06/30")).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(FechaLegacyParser.parseOrNull("30-06-2024")).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(FechaLegacyParser.parseOrNull("30/06/2024")).isEqualTo(LocalDate.of(2024, 6, 30));
    }

    @Test
    void deberia_retornar_null_si_el_formato_no_es_reconocible() {
        assertThat(FechaLegacyParser.parseOrNull("fecha-invalida")).isNull();
        assertThat(FechaLegacyParser.parseOrNull("")).isNull();
        assertThat(FechaLegacyParser.parseOrNull(null)).isNull();
    }
}
