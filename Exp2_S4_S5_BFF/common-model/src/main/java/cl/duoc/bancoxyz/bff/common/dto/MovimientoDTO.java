package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/** Un movimiento del historial de una cuenta. */
public record MovimientoDTO(
        Long cuentaId,
        String fecha,
        String tipoMovimiento,
        BigDecimal monto,
        String descripcion
) {
}
