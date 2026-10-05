package cl.duoc.bancoxyz.common.dto;

import java.util.List;

/**
 * Vista agregada que devuelve movimientos-service: los datos de la cuenta
 * (que son de otro microservicio) junto al historial y los totales propios.
 *
 * {@code origenDatosCuenta} es parte del contrato a proposito: cuando
 * cuentas-service no responde, el circuit breaker entrega la ficha igual con
 * los datos de cuenta en blanco, y el cliente necesita saber que lo que recibio
 * es una respuesta degradada y no un dato real.
 */
public record FichaCuentaDTO(
        Long cuentaId,
        CuentaDTO cuenta,
        String origenDatosCuenta,
        ResumenMovimientosDTO resumen,
        List<MovimientoDTO> movimientos
) {
}
