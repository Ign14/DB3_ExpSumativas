package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/**
 * Resultado de un retiro: aprobado con el saldo resultante, o rechazado con el
 * motivo.
 *
 * {@code eventoPublicado} se expone a proposito. El dinero sale de la cuenta en
 * cuentas-service y el movimiento queda en el historial de movimientos-service
 * al consumir el evento. Si el broker no acepta el evento, el retiro ya ocurrio
 * y no se revierte: el cliente tiene derecho a saber que su comprobante puede
 * tardar en aparecer en el historial.
 */
public record RetiroResponse(
        Long cuentaId,
        BigDecimal montoSolicitado,
        BigDecimal saldoResultante,
        boolean aprobado,
        String motivoRechazo,
        String eventoId,
        boolean eventoPublicado
) {
}
