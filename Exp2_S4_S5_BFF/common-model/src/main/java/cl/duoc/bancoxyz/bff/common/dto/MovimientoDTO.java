package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/**
 * Un movimiento (deposito/retiro) del historial de una cuenta, tal como lo
 * expone core-movimientos-service a partir de cuentas_anuales.csv.
 */
public record MovimientoDTO(
        Long cuentaId,
        String fecha,
        String tipoMovimiento,
        BigDecimal monto,
        String descripcion
) {
}
