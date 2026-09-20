package cl.duoc.bancoxyz.bff.movimientos.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FechaLegacyParserTest {

    @ParameterizedTest(name = "interpreta \"{0}\" como {1}")
    @CsvSource({
            "2024-03-08, 2024-03-08",
            "2024/03/08, 2024-03-08",
            "08-03-2024, 2024-03-08",
            "08/03/2024, 2024-03-08"
    })
    @DisplayName("Interpreta los 4 formatos de fecha que mezcla el dataset legacy")
    void interpretaLosCuatroFormatos(String entrada, String esperado) {
        assertEquals(LocalDate.parse(esperado), FechaLegacyParser.parsear(entrada).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "fecha-invalida", "2024-13-45", "31/02/2024"})
    @DisplayName("Rechaza fechas vacias o no interpretables en vez de inventar una")
    void rechazaFechasInvalidas(String entrada) {
        assertTrue(FechaLegacyParser.parsear(entrada).isEmpty());
    }

    @Test
    @DisplayName("Rechaza null sin lanzar excepcion")
    void rechazaNull() {
        assertTrue(FechaLegacyParser.parsear(null).isEmpty());
    }
}
