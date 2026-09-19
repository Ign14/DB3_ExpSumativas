package cl.duoc.bancoxyz.batch.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Los tres archivos legacy del Banco XYZ traen fechas en formatos
 * inconsistentes (mezcla de {@code yyyy-MM-dd}, {@code yyyy/MM/dd},
 * {@code dd-MM-yyyy} y {@code dd/MM/yyyy} dentro del mismo archivo), tal
 * como describe el README del dataset. Esta utilidad intenta parsear el
 * valor probando los formatos conocidos en orden y retorna {@code null}
 * si ninguno aplica, dejando que el ItemProcessor decida tratarlo como
 * anomalía.
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

    public static LocalDate parseOrNull(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String texto = valor.trim();
        for (DateTimeFormatter formatter : FORMATOS) {
            try {
                return LocalDate.parse(texto, formatter);
            } catch (Exception ignored) {
                // se intenta con el siguiente formato conocido
            }
        }
        return null;
    }
}
