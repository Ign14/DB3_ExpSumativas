package cl.duoc.bancoxyz.bff.movimientos.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * El dataset legacy mezcla 4 formatos de fecha en la misma columna
 * (yyyy-MM-dd, yyyy/MM/dd, dd-MM-yyyy, dd/MM/yyyy). Se reintenta con cada
 * uno, igual que se hizo en el Job de migracion batch de la semana 3.
 */
public final class FechaLegacyParser {

    private static final List<DateTimeFormatter> FORMATOS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    );

    private FechaLegacyParser() {
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
