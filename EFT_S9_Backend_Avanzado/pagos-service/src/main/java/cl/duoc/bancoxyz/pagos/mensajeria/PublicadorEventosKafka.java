package cl.duoc.bancoxyz.pagos.mensajeria;

import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica en Kafka los hechos del dominio de pagos: la operacion completada y
 * las alertas que produce este servicio, incluida la que avisa que una
 * dependencia se degrado.
 *
 * La clave del mensaje es la cuenta de origen, por la misma razon que en
 * cuentas-service: Kafka ordena dentro de una particion, y una misma clave cae
 * siempre en la misma particion, asi que los eventos de una cuenta se consumen
 * en el orden en que ocurrieron aunque el topico tenga varias particiones.
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
            log.info(">> pagos-service: evento {} publicado en {} ({} de {} de la cuenta {} a la {})",
                    evento.eventoId(), TransaccionCompletadaEvento.TOPICO, evento.tipoOperacion(),
                    evento.monto(), evento.cuentaOrigen(), evento.cuentaDestino());
            return true;
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            log.error(">> pagos-service: NO se pudo publicar el evento {} en {} - "
                            + "la operacion ya se aplico y el evento debera reponerse: {}",
                    evento.eventoId(), TransaccionCompletadaEvento.TOPICO, ex.getMessage());
            return false;
        }
    }

    public void publicarAlerta(AlertaSeguridadEvento evento) {
        try {
            kafkaTemplate.send(AlertaSeguridadEvento.TOPICO, clave(evento.cuentaId()), evento).get();
            log.info(">> pagos-service: alerta {} publicada en {} (severidad {})",
                    evento.tipoAlerta(), AlertaSeguridadEvento.TOPICO, evento.severidad());
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            log.warn(">> pagos-service: no se pudo publicar la alerta {}: {}",
                    evento.tipoAlerta(), ex.getMessage());
        }
    }

    private static String clave(Long cuentaId) {
        return cuentaId == null ? null : String.valueOf(cuentaId);
    }
}
