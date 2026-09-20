package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/**
 * Agregado del log de transacciones diarias del banco, que no está asociado a
 * ninguna cuenta en particular.
 */
public record TransaccionDiariaResumenDTO(
        long cantidadTotal,
        BigDecimal montoTotal,
        long cantidadCreditos,
        long cantidadDebitos,
        BigDecimal montoCreditos,
        BigDecimal montoDebitos
) {
}
