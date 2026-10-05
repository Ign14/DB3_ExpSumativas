package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/**
 * Totales del historial de una cuenta.
 *
 * {@code totalEgresos} agrupa retiros, compras y pagos porque los tres sacan
 * dinero de la cuenta; se nombra asi, y no "retiros", para que el dato no se
 * lea como algo mas estrecho de lo que es.
 */
public record ResumenMovimientosDTO(
        Long cuentaId,
        int totalMovimientos,
        BigDecimal totalDepositos,
        BigDecimal totalEgresos,
        String ultimaFecha
) {
}
