package cl.duoc.bancoxyz.bff.movil.dto;

import java.math.BigDecimal;
import java.util.List;

/** Payload liviano del canal móvil. */
public record CuentaMovilResponse(
        BigDecimal saldo,
        String tipoCuenta,
        List<MovimientoMovilDTO> ultimosMovimientos
) {
}
