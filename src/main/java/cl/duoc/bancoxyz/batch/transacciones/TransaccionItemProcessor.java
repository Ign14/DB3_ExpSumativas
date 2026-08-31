package cl.duoc.bancoxyz.batch.transacciones;

import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.common.util.FechaLegacyParser;
import cl.duoc.bancoxyz.batch.transacciones.model.Transaccion;
import cl.duoc.bancoxyz.batch.transacciones.model.TransaccionCsv;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Valida y transforma cada fila de {@code transacciones.csv}.
 * <p>
 * Reglas de consistencia aplicadas (basadas en los problemas simulados que
 * describe el README del dataset legacy: montos negativos/cero, formatos de
 * fecha inconsistentes y tipos de transacción inválidos):
 * <ul>
 *   <li>El monto debe existir y ser mayor que cero.</li>
 *   <li>La fecha debe poder interpretarse en alguno de los formatos legacy
 *       conocidos ({@link FechaLegacyParser}).</li>
 *   <li>El tipo debe pertenecer al dominio {@code credito, debito}; valores
 *       como {@code invalid} o {@code desconocido} se rechazan.</li>
 * </ul>
 * Cuando una regla no se cumple se lanza {@link RegistroInvalidoException},
 * que activa la política de skip configurada en el Step: el registro queda
 * fuera del reporte, pero el resto del archivo se sigue procesando.
 */
public class TransaccionItemProcessor implements ItemProcessor<TransaccionCsv, Transaccion> {

    private static final Set<String> TIPOS_VALIDOS = Set.of("credito", "debito");

    @Override
    public Transaccion process(TransaccionCsv item) {
        Long id = parseId(item.getId());

        LocalDate fecha = FechaLegacyParser.parseOrNull(item.getFecha());
        if (fecha == null) {
            throw new RegistroInvalidoException(
                    "id=" + item.getId() + " fecha no interpretable: '" + item.getFecha() + "'");
        }

        BigDecimal monto = parseMonto(item.getMonto());
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RegistroInvalidoException(
                    "id=" + item.getId() + " monto inválido: '" + item.getMonto() + "'");
        }

        String tipo = item.getTipo() == null ? "" : item.getTipo().trim().toLowerCase();
        if (!TIPOS_VALIDOS.contains(tipo)) {
            throw new RegistroInvalidoException(
                    "id=" + item.getId() + " tipo de transacción fuera de dominio: '" + item.getTipo() + "'");
        }

        return new Transaccion(id, fecha, monto, tipo, LocalDateTime.now());
    }

    private Long parseId(String valor) {
        try {
            return Long.valueOf(valor.trim());
        } catch (Exception e) {
            throw new RegistroInvalidoException("id no numérico: '" + valor + "'");
        }
    }

    private BigDecimal parseMonto(String valor) {
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
