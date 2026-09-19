package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/**
 * Agregado del log de transacciones diarias del banco (transacciones.csv),
 * que no esta asociado a una cuenta especifica. Lo consume, por ejemplo,
 * un panel administrativo del BFF Web.
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
