package cl.duoc.bancoxyz.bff.movimientos.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;

/**
 * El dataset legacy mezcla cuatro formatos de fecha en la misma columna, así
 * que se intenta con cada uno.
 *
 * Se resuelve en modo estricto (que exige el patrón {@code uuuu}) porque el
 * modo por defecto no rechaza una fecha imposible como 31/02/2024: la ajusta
 * en silencio al 29 de febrero. Al validar datos sucios, convertir basura en
 * una fecha plausible es peor que descartarla.
 */
public final class FechaLegacyParser {

    private static final List<DateTimeFormatter> FORMATOS = List.of(
            estricto("uuuu-MM-dd"),
            estricto("uuuu/MM/dd"),
            estricto("dd-MM-uuuu"),
            estricto("dd/MM/uuuu")
    );

    private FechaLegacyParser() {
    }

    private static DateTimeFormatter estricto(String patron) {
        return DateTimeFormatter.ofPattern(patron).withResolverStyle(ResolverStyle.STRICT);
    }

    public static Optional<LocalDate> parsear(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        for (DateTimeFormatter formato : FORMATOS) {
            try {
                return Optional.of(LocalDate.parse(texto.trim(), formato));
            } catch (Exception ignored) {
                // se intenta con el siguiente formato
            }
        }
        return Optional.empty();
    }
}
