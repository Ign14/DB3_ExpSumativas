package cl.duoc.bancoxyz.cuentas.mensajeria;

import cl.duoc.bancoxyz.common.evento.RetiroRealizadoEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica en la cola el evento de retiro aprobado.
 *
 * El fallo al publicar no se propaga: el debito ya se aplico y lanzar aqui
 * haria que el cliente recibiera un error por una operacion que si ocurrio.
 * Se devuelve false, queda en el log con el identificador del evento para poder
 * reponerlo a mano, y el comprobante se lo informa al cliente.
 */
@Component
public class PublicadorRetiros {

    private static final Logger log = LoggerFactory.getLogger(PublicadorRetiros.class);

    private final JmsTemplate jmsTemplate;

    public PublicadorRetiros(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public boolean publicar(RetiroRealizadoEvento evento) {
        try {
            jmsTemplate.convertAndSend(RetiroRealizadoEvento.COLA, evento);
            log.info(">> cuentas-service: evento {} publicado en la cola {} (cuenta {}, monto {})",
                    evento.eventoId(), RetiroRealizadoEvento.COLA, evento.cuentaId(), evento.monto());
            return true;
        } catch (Exception ex) {
            log.error(">> cuentas-service: NO se pudo publicar el evento {} de la cuenta {} por {} - "
                            + "el debito ya se aplico y el movimiento debera reponerse",
                    evento.eventoId(), evento.cuentaId(), evento.monto(), ex);
            return false;
        }
    }
}
