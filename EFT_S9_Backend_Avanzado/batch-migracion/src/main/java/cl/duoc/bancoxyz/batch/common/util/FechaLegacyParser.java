package cl.duoc.bancoxyz.batch.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;

/**
 * Los tres archivos legacy traen fechas en formatos inconsistentes (mezcla de
 * {@code yyyy-MM-dd}, {@code yyyy/MM/dd}, {@code dd-MM-yyyy} y
 * {@code dd/MM/yyyy} dentro del mismo archivo), tal como describe el README del
 * dataset. Esta utilidad intenta parsear el valor probando los formatos
 * conocidos en orden y devuelve {@code null} si ninguno aplica, dejando que el
 * ItemProcessor decida tratarlo como anomalia.
 *
 * <h2>Por que el modo estricto</h2>
 *
 * Los formatos se construyen con {@link ResolverStyle#STRICT} y con el patron
 * {@code uuuu} en vez de {@code yyyy}, y esas dos cosas van juntas.
 *
 * En el modo por defecto (SMART), {@code DateTimeFormatter} acepta
 * {@code 31/02/2024} y lo convierte en el 29 de febrero sin avisar. Para un
 * proceso cuyo trabajo es limpiar datos sucios, eso es lo peor que puede pasar:
 * el parser "funciona", nadie ve un error, y el dato queda corrompido con una
 * fecha que el archivo original no decia. Un framework que arregla la basura
 * por su cuenta es mas peligroso que uno que falla.
 *
 * El cambio de {@code yyyy} a {@code uuuu} no es cosmetico: en modo estricto,
 * {@code yyyy} significa "ano de la era" y exige que el patron declare tambien
 * la era, asi que un formato estricto con {@code yyyy} falla al parsear
 * cualquier fecha. {@code uuuu} es el ano proleptico y es el que corresponde
 * cuando no hay era en el patron.
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

    /**
     * @return la fecha interpretada, o {@code null} si el valor esta vacio, no
     *         corresponde a ninguno de los formatos conocidos, o describe un dia
     *         que no existe.
     */
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
