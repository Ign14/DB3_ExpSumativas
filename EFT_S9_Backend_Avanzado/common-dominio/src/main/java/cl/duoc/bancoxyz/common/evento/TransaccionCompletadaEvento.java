package cl.duoc.bancoxyz.common.evento;

import java.math.BigDecimal;

/**
 * Evento de negocio que se publica cuando una operacion sobre el dinero de una
 * cuenta termina aprobada: un retiro, un deposito o una transferencia.
 *
 * Viaja por Kafka y no por la cola JMS, y la diferencia no es de gusto. El
 * evento de retiro ({@link RetiroRealizadoEvento}) es una instruccion dirigida a
 * un consumidor concreto: pagos-service tiene que anotar ese movimiento en el
 * historial, una sola vez, y si el consumidor esta caido el mensaje debe
 * esperarlo. Eso es una cola, y JMS la modela bien.
 *
 * Este evento, en cambio, es un hecho: "esta transaccion ocurrio". No sabe
 * quien lo necesita ni cuantos son. Hoy lo consume clientes-service para
 * mantener la actividad del titular; manana podria sumarse un servicio de
 * analitica o uno de notificaciones sin que el productor cambie una linea. Para
 * eso sirve un topico con retencion y consumidores independientes, cada uno con
 * su propio avance de lectura, que es exactamente lo que ofrece Kafka.
 */
public record TransaccionCompletadaEvento(
        String eventoId,
        String tipoOperacion,
        Long cuentaOrigen,
        Long cuentaDestino,
        BigDecimal monto,
        BigDecimal saldoResultante,
        String fecha,
        String canal
) {
    /** Topico de Kafka por el que viaja este evento. */
    public static final String TOPICO = "banco.transacciones-completadas";

    public static final String RETIRO = "RETIRO";
    public static final String DEPOSITO = "DEPOSITO";
    public static final String TRANSFERENCIA = "TRANSFERENCIA";
}
