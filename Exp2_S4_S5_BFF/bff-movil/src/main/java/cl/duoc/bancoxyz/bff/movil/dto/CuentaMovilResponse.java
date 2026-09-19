package cl.duoc.bancoxyz.bff.movil.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload liviano: sin nombre del titular (la app ya lo tiene de la sesion
 * activa), sin descripciones de movimiento, y con solo los ultimos
 * movimientos en vez del historial completo.
 */
public record CuentaMovilResponse(
        BigDecimal saldo,
        String tipoCuenta,
        List<MovimientoMovilDTO> ultimosMovimientos
) {
}
