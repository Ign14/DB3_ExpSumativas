package cl.duoc.bancoxyz.common.evento;

import java.math.BigDecimal;

/**
 * Evento que publica cuentas-service cada vez que aprueba un retiro.
 * movimientos-service lo consume para dejar el movimiento en el historial.
 *
 * Es el contrato de la cola: ambos microservicios dependen de esta clase y de
 * nada más del otro servicio.
 */
public record RetiroRealizadoEvento(
        String eventoId,
        Long cuentaId,
        BigDecimal monto,
        BigDecimal saldoResultante,
        String fecha,
        String canal
) {
    /** Nombre de la cola JMS por la que viaja este evento. */
    public static final String COLA = "banco.retiros";
}
