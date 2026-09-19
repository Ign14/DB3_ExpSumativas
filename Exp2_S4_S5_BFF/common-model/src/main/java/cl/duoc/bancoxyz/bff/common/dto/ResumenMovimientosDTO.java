package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/**
 * Agregado del historial de movimientos de una cuenta. Se calcula una sola
 * vez en core-movimientos-service para que ningun BFF tenga que recorrer el
 * historial completo solo para mostrar totales.
 */
public record ResumenMovimientosDTO(
        Long cuentaId,
        int cantidadMovimientos,
        BigDecimal totalDepositos,
        BigDecimal totalRetiros,
        String ultimoMovimientoFecha
) {
}
