package cl.duoc.bancoxyz.bff.movimientos.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;

/**
 * El dataset legacy mezcla 4 formatos de fecha en la misma columna
 * (yyyy-MM-dd, yyyy/MM/dd, dd-MM-yyyy, dd/MM/yyyy). Se reintenta con cada
 * uno, igual que se hizo en el Job de migracion batch de la semana 3.
 *
 * <p>Se usa {@link ResolverStyle#STRICT} (con el patron {@code uuuu}, que
 * es el que exige el modo estricto) y no el modo SMART por defecto: en modo
 * SMART una fecha imposible como "31/02/2024" no falla, sino que se ajusta
 * silenciosamente al 29 de febrero. Para datos legacy que justamente se
 * estan validando, convertir basura en una fecha plausible es peor que
 * rechazarla: el registro debe quedar omitido y contabilizado como
 * inconsistente.</p>
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
