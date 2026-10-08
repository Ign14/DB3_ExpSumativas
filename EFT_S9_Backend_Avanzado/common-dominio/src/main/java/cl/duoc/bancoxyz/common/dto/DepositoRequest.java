package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/** Solicitud de deposito a una cuenta. */
public record DepositoRequest(
        BigDecimal monto,
        String canal,
        String descripcion
) {
}
