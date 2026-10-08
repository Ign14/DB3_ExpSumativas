package cl.duoc.bancoxyz.pagos.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FechaLegacyParserTest {

    @Test
    @DisplayName("Interpreta los cuatro formatos que mezcla el dataset legacy")
    void losCuatroFormatos() {
        LocalDate esperada = LocalDate.of(2024, 3, 8);
        assertEquals(esperada, FechaLegacyParser.parsear("2024-03-08").orElseThrow());
        assertEquals(esperada, FechaLegacyParser.parsear("2024/03/08").orElseThrow());
        assertEquals(esperada, FechaLegacyParser.parsear("08-03-2024").orElseThrow());
        assertEquals(esperada, FechaLegacyParser.parsear("08/03/2024").orElseThrow());
    }

    @Test
    @DisplayName("Ignora los espacios alrededor del valor")
    void toleraEspacios() {
        assertEquals(LocalDate.of(2024, 7, 24), FechaLegacyParser.parsear("  24-07-2024  ").orElseThrow());
    }

    /**
     * El modo por defecto de DateTimeFormatter no rechaza esta fecha: la ajusta
     * en silencio al 29 de febrero. Al validar datos sucios, inventar una fecha
     * plausible es peor que descartar la fila, y de ahi el ResolverStyle.STRICT.
     */
    @Test
    @DisplayName("Rechaza una fecha imposible en vez de corregirla sola")
    void rechazaFechaImposible() {
        assertTrue(FechaLegacyParser.parsear("31/02/2024").isEmpty());
        assertTrue(FechaLegacyParser.parsear("2024-13-01").isEmpty());
        assertTrue(FechaLegacyParser.parsear("2023-02-29").isEmpty());
    }

    @Test
    @DisplayName("Devuelve vacio ante nulo, vacio o texto que no es una fecha")
    void valoresNoInterpretables() {
        assertTrue(FechaLegacyParser.parsear(null).isEmpty());
        assertTrue(FechaLegacyParser.parsear("").isEmpty());
        assertTrue(FechaLegacyParser.parsear("   ").isEmpty());
        assertTrue(FechaLegacyParser.parsear("ayer").isEmpty());
    }
}
