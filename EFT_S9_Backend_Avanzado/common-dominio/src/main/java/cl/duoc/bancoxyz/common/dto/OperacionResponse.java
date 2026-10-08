package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/**
 * Comprobante de una operacion de pago: deposito o transferencia.
 *
 * Igual que el comprobante de retiro, informa por separado si la operacion se
 * aprobo y si el evento alcanzo a publicarse. Son dos cosas distintas y
 * mezclarlas obligaria al cliente a adivinar: el dinero puede haberse movido y
 * el evento no haber salido, y en ese caso lo que corresponde es que quien
 * consulta lo sepa, no que reciba un error por una operacion que si ocurrio.
 */
public record OperacionResponse(
        String operacionId,
        String tipoOperacion,
        Long cuentaOrigen,
        Long cuentaDestino,
        BigDecimal monto,
        BigDecimal saldoResultante,
        boolean aprobada,
        String motivoRechazo,
        boolean eventoPublicado
) {
}
