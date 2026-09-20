package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/** Resultado de un débito: aprobado con el saldo resultante, o rechazado con el motivo. */
public record DebitoResponse(
        Long cuentaId,
        BigDecimal montoSolicitado,
        BigDecimal saldoResultante,
        boolean aprobado,
        String motivoRechazo
) {
}
