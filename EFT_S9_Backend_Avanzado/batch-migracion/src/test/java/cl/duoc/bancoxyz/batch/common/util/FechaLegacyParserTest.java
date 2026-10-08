package cl.duoc.bancoxyz.batch.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FechaLegacyParserTest {

    @Test
    @DisplayName("Interpreta los cuatro formatos legacy conocidos")
    void deberia_interpretar_los_cuatro_formatos_legacy_conocidos() {
        assertThat(FechaLegacyParser.parseOrNull("2024-06-30")).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(FechaLegacyParser.parseOrNull("2024/06/30")).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(FechaLegacyParser.parseOrNull("30-06-2024")).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(FechaLegacyParser.parseOrNull("30/06/2024")).isEqualTo(LocalDate.of(2024, 6, 30));
    }

    @Test
    @DisplayName("Devuelve null si el formato no es reconocible")
    void deberia_retornar_null_si_el_formato_no_es_reconocible() {
        assertThat(FechaLegacyParser.parseOrNull("fecha-invalida")).isNull();
        assertThat(FechaLegacyParser.parseOrNull("")).isNull();
        assertThat(FechaLegacyParser.parseOrNull(null)).isNull();
    }

    /**
     * Este es el test que de verdad justifica el modo estricto, y el que faltaba.
     *
     * En el modo por defecto, {@code DateTimeFormatter} acepta {@code 31/02/2024}
     * y devuelve el 29 de febrero, sin lanzar nada. Un parser asi "funciona" y
     * corrompe datos en silencio: el proceso batch escribiria una fecha que el
     * archivo de origen nunca dijo, y nadie se enteraria porque no hay error.
     *
     * Vale la pena anotar como sobrevivio este defecto: la version de este mismo
     * parser que vive en pagos-service si era estricta y si tenia este test, pero
     * la del batch no, y ninguna prueba comparaba las dos. Dos copias del mismo
     * codigo con suites separadas es exactamente la forma en que una garantia se
     * pierde sin que nada falle.
     */
    @Test
    @DisplayName("Rechaza dias que no existen en vez de corregirlos en silencio")
    void deberia_rechazar_fechas_imposibles() {
        assertThat(FechaLegacyParser.parseOrNull("31/02/2024"))
                .as("el 31 de febrero no existe: el modo por defecto lo convertiria en el 29")
                .isNull();
        assertThat(FechaLegacyParser.parseOrNull("2023-02-29"))
                .as("2023 no es bisiesto")
                .isNull();
        assertThat(FechaLegacyParser.parseOrNull("2024-13-01"))
                .as("no existe el mes 13")
                .isNull();
        assertThat(FechaLegacyParser.parseOrNull("31-04-2024"))
                .as("abril tiene 30 dias")
                .isNull();
        assertThat(FechaLegacyParser.parseOrNull("2024-00-10"))
                .as("no existe el mes 0")
                .isNull();
    }

    @Test
    @DisplayName("El 29 de febrero de un ano bisiesto si es una fecha valida")
    void deberia_aceptar_el_29_de_febrero_en_ano_bisiesto() {
        // El complemento del test anterior: el modo estricto rechaza lo imposible,
        // no lo infrecuente. Sin esta comprobacion, un parser que rechazara todo
        // febrero pasaria igual.
        assertThat(FechaLegacyParser.parseOrNull("2024-02-29")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(FechaLegacyParser.parseOrNull("29/02/2024")).isEqualTo(LocalDate.of(2024, 2, 29));
    }
}
