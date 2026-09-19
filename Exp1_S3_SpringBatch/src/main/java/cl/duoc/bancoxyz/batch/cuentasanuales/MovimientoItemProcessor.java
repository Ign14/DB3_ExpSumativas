package cl.duoc.bancoxyz.batch.cuentasanuales;

import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.common.util.FechaLegacyParser;
import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCsv;
import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCuentaAnual;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Valida, corrige y transforma cada fila de {@code cuentas_anuales.csv}
 * para compilar el estado de cuenta anual de auditoría.
 * <p>
 * A diferencia de los otros dos procesadores, aquí se combinan las dos
 * estrategias que pide el enunciado ("corregir o manejar los errores"):
 * <ul>
 *   <li><b>Corrección</b>: se normaliza el tipo de movimiento (por ejemplo
 *       {@code depósito} -&gt; {@code deposito}) y se completa la
 *       descripción vacía con un valor por defecto, en vez de descartar
 *       el registro por un problema menor y recuperable.</li>
 *   <li><b>Rechazo (skip)</b>: fecha no interpretable, monto vacío/negativo
 *       o tipo de movimiento fuera de dominio, por ser inconsistencias que
 *       no se pueden subsanar de forma segura.</li>
 * </ul>
 */
public class MovimientoItemProcessor implements ItemProcessor<MovimientoCsv, MovimientoCuentaAnual> {

    private static final Set<String> TIPOS_VALIDOS = Set.of("deposito", "retiro", "compra", "pago");
    private static final String DESCRIPCION_POR_DEFECTO = "Sin descripción registrada";

    @Override
    public MovimientoCuentaAnual process(MovimientoCsv item) {
        Long cuentaId = parseLong(item.getCuentaId());

        LocalDate fecha = FechaLegacyParser.parseOrNull(item.getFecha());
        if (fecha == null) {
            throw new RegistroInvalidoException(
                    "cuenta_id=" + item.getCuentaId() + " fecha no interpretable: '" + item.getFecha() + "'");
        }

        BigDecimal monto = parseDecimal(item.getMonto());
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RegistroInvalidoException(
                    "cuenta_id=" + item.getCuentaId() + " monto inválido: '" + item.getMonto() + "'");
        }

        // Corrección: se normaliza (sin tildes, minúsculas) en vez de descartar.
        String tipoNormalizado = normalizar(item.getTransaccion());
        if (!TIPOS_VALIDOS.contains(tipoNormalizado)) {
            throw new RegistroInvalidoException(
                    "cuenta_id=" + item.getCuentaId() + " tipo de movimiento fuera de dominio: '" + item.getTransaccion() + "'");
        }

        // Corrección: descripción vacía se completa con un valor por defecto.
        String descripcion = (item.getDescripcion() == null || item.getDescripcion().isBlank())
                ? DESCRIPCION_POR_DEFECTO
                : item.getDescripcion().trim();

        return new MovimientoCuentaAnual(null, cuentaId, fecha, fecha.getYear(), tipoNormalizado, monto,
                descripcion, LocalDateTime.now());
    }

    private String normalizar(String valor) {
        if (valor == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(valor.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinTildes;
    }

    private Long parseLong(String valor) {
        try {
            return Long.valueOf(valor.trim());
        } catch (Exception e) {
            throw new RegistroInvalidoException("cuenta_id no numérico: '" + valor + "'");
        }
    }

    private BigDecimal parseDecimal(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
