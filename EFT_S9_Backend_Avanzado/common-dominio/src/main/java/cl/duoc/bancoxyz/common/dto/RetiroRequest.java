package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/** Solicitud de retiro sobre una cuenta. */
public record RetiroRequest(BigDecimal monto, String canal) {
}
