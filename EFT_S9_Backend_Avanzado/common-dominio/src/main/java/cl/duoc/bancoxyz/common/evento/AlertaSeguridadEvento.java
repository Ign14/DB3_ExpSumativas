package cl.duoc.bancoxyz.common.evento;

/**
 * Evento que se publica cuando ocurre algo que alguien deberia mirar: un intento
 * de retiro sobre el limite por operacion, un rechazo por fondos insuficientes,
 * o un circuito que se abre porque una dependencia dejo de responder.
 *
 * Es el segundo topico que pide el caso. Comparte con
 * {@link TransaccionCompletadaEvento} la razon de estar en Kafka y no en una
 * cola: una alerta no tiene un destinatario unico. Se publica una vez y la leen
 * por separado quien la registra, quien la cuenta y quien manana decida
 * notificarla, sin que el productor sepa de ninguno de ellos.
 *
 * La severidad va como texto y no como enum a proposito: es un contrato entre
 * procesos que se despliegan por separado, y agregar un valor nuevo al enum del
 * productor haria fallar la deserializacion en un consumidor que todavia no se
 * actualizo.
 */
public record AlertaSeguridadEvento(
        String eventoId,
        String tipoAlerta,
        Long cuentaId,
        String detalle,
        String severidad,
        String fecha
) {
    /** Topico de Kafka por el que viaja este evento. */
    public static final String TOPICO = "banco.alertas-seguridad";

    public static final String SEVERIDAD_INFO = "INFO";
    public static final String SEVERIDAD_MEDIA = "MEDIA";
    public static final String SEVERIDAD_ALTA = "ALTA";

    public static final String RETIRO_SOBRE_LIMITE = "RETIRO_SOBRE_LIMITE";
    public static final String FONDOS_INSUFICIENTES = "FONDOS_INSUFICIENTES";
    public static final String DEPENDENCIA_DEGRADADA = "DEPENDENCIA_DEGRADADA";
    /**
     * La compensacion de una transferencia a medio aplicar no se pudo ejecutar:
     * hay dinero descontado de una cuenta que no llego a la otra ni volvio al
     * origen. Es la unica alerta que nace siempre con severidad ALTA, porque es
     * la unica que describe un descalce contable que no se arregla solo.
     */
    public static final String COMPENSACION_FALLIDA = "COMPENSACION_FALLIDA";
}
