package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/**
 * Totales del historial de una cuenta, calculados en core-movimientos-service
 * para que ningún BFF tenga que recorrer el historial completo solo para
 * mostrar sumas.
 *
 * {@code totalEgresos} agrupa retiros, compras y pagos: los tres sacan dinero
 * de la cuenta.
 */
public record ResumenMovimientosDTO(
        Long cuentaId,
        int cantidadMovimientos,
        BigDecimal totalDepositos,
        BigDecimal totalEgresos,
        String ultimoMovimientoFecha
) {
}
