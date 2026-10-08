package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/** Solicitud de transferencia entre dos cuentas del banco. */
public record TransferenciaRequest(
        Long cuentaDestino,
        BigDecimal monto,
        String canal,
        String descripcion
) {
}
