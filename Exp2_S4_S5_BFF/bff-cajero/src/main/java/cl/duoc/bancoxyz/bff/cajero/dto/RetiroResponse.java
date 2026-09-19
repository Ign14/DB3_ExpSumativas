package cl.duoc.bancoxyz.bff.cajero.dto;

import java.math.BigDecimal;

public record RetiroResponse(
        Long cuentaId,
        BigDecimal montoSolicitado,
        BigDecimal saldoResultante,
        boolean aprobado,
        String motivoRechazo
) {
}
