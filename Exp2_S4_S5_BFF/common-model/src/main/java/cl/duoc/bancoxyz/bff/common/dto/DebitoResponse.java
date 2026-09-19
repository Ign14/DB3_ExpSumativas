package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/**
 * Resultado de aplicar (o rechazar) un debito. core-cuentas-service es quien
 * decide si hay fondos suficientes; el BFF que llamo (por ejemplo, el de
 * cajero) solo traduce esto a la respuesta de su canal.
 */
public record DebitoResponse(
        Long cuentaId,
        BigDecimal montoSolicitado,
        BigDecimal saldoResultante,
        boolean aprobado,
        String motivoRechazo
) {
}
