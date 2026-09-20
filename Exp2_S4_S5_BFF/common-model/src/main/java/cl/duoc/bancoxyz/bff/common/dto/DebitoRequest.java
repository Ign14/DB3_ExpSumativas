package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/** Solicitud de débito sobre el saldo de una cuenta. */
public record DebitoRequest(BigDecimal monto) {
}
