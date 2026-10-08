package cl.duoc.bancoxyz.cuentas.mensajeria;

import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica en Kafka los hechos del dominio de cuentas: la transaccion que se
 * completo y la alerta de seguridad que alguien deberia mirar.
 *
 * Igual que el publicador JMS, un fallo al publicar no se propaga: el saldo ya
 * se movio y lanzar aqui haria que el cliente recibiera un error por una
 * operacion que si ocurrio. Queda en el log con el identificador del evento, se
 * informa en el comprobante, y el que decide que hacer es quien consulta.
 *
 * La clave del mensaje es el numero de cuenta, y esa eleccion tiene una
 * consecuencia util: Kafka garantiza el orden dentro de una particion, y todos
 * los eventos de una misma cuenta caen en la misma particion por tener la misma
 * clave. Asi un consumidor nunca ve el segundo retiro de una cuenta antes que el
 * primero, aunque el topico tenga varias particiones y varios consumidores.
 */
@Component
public class PublicadorEventosKafka {

    private static final Logger log = LoggerFactory.getLogger(PublicadorEventosKafka.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PublicadorEventosKafka(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public boolean publicarTransaccion(TransaccionCompletadaEvento evento) {
        try {
            kafkaTemplate.send(TransaccionCompletadaEvento.TOPICO,
                    clave(evento.cuentaOrigen()), evento).get();
            log.info(">> cuentas-service: evento {} publicado en el topico {} ({} de {} en la cuenta {})",
                    evento.eventoId(), TransaccionCompletadaEvento.TOPICO,
                    evento.tipoOperacion(), evento.monto(), evento.cuentaOrigen());
            return true;
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            log.error(">> cuentas-service: NO se pudo publicar el evento {} en {} - "
                            + "la operacion ya se aplico y el evento debera reponerse: {}",
                    evento.eventoId(), TransaccionCompletadaEvento.TOPICO, ex.getMessage());
            return false;
        }
    }

    /**
     * Publica una alerta. No devuelve nada a proposito: una alerta que no se
     * pudo publicar no cambia la respuesta al cliente, porque la operacion que
     * la provoco ya fue rechazada y el cliente ya tiene su motivo.
     */
    public void publicarAlerta(AlertaSeguridadEvento evento) {
        try {
            kafkaTemplate.send(AlertaSeguridadEvento.TOPICO, clave(evento.cuentaId()), evento).get();
            log.info(">> cuentas-service: alerta {} publicada en el topico {} (cuenta {}, severidad {})",
                    evento.tipoAlerta(), AlertaSeguridadEvento.TOPICO,
                    evento.cuentaId(), evento.severidad());
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            log.warn(">> cuentas-service: no se pudo publicar la alerta {} de la cuenta {}: {}",
                    evento.tipoAlerta(), evento.cuentaId(), ex.getMessage());
        }
    }

    private static String clave(Long cuentaId) {
        return cuentaId == null ? null : String.valueOf(cuentaId);
    }
}
