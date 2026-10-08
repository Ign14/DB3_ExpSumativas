package cl.duoc.bancoxyz.clientes.mensajeria;

import cl.duoc.bancoxyz.clientes.domain.ClienteRepositoryEnMemoria;
import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Mantiene al dia la vista local de la actividad de cada cliente escuchando los
 * dos topicos.
 *
 * Los dos listeners van en el mismo grupo de consumidores. Eso significa que al
 * levantar una segunda instancia de clientes-service, Kafka reparte las
 * particiones entre ambas y cada evento se procesa una sola vez en el conjunto,
 * no una vez por instancia: es lo que permite escalar el servicio
 * horizontalmente sin duplicar los contadores. Si lo que se quisiera fuera que
 * todas las instancias vieran todos los eventos (un cache local, por ejemplo),
 * el grupo tendria que ser distinto en cada una.
 *
 * Una transferencia mueve dinero en dos cuentas y por eso se anota en las dos
 * cuando ambas vienen informadas. Contarla solo en el origen dejaria al titular
 * que recibe el dinero con actividad cero despues de haber recibido un abono.
 */
@Component
public class ConsumidorEventos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEventos.class);

    private final ClienteRepositoryEnMemoria repositorio;

    public ConsumidorEventos(ClienteRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    @KafkaListener(
            topics = TransaccionCompletadaEvento.TOPICO,
            containerFactory = "factoriaTransacciones",
            groupId = "${banco.kafka.grupo-clientes}")
    public void alCompletarseUnaTransaccion(TransaccionCompletadaEvento evento) {
        String descripcion = evento.tipoOperacion() + " de " + evento.monto() + " el " + evento.fecha();
        if (evento.cuentaOrigen() != null) {
            repositorio.registrarOperacion(evento.cuentaOrigen(), descripcion);
        }
        if (evento.cuentaDestino() != null && !evento.cuentaDestino().equals(evento.cuentaOrigen())) {
            repositorio.registrarOperacion(evento.cuentaDestino(), descripcion);
        }
        log.info(">> clientes-service: evento {} de Kafka anotado - {} sobre cuenta origen {} y destino {}",
                evento.eventoId(), evento.tipoOperacion(), evento.cuentaOrigen(), evento.cuentaDestino());
    }

    @KafkaListener(
            topics = AlertaSeguridadEvento.TOPICO,
            containerFactory = "factoriaAlertas",
            groupId = "${banco.kafka.grupo-clientes}")
    public void alLlegarUnaAlerta(AlertaSeguridadEvento evento) {
        if (evento.cuentaId() != null) {
            repositorio.registrarAlerta(evento.cuentaId());
        }
        // El nivel del log sigue a la severidad del evento y no es decorativo:
        // una alerta ALTA es la que alguien tiene que mirar hoy.
        if (AlertaSeguridadEvento.SEVERIDAD_ALTA.equals(evento.severidad())) {
            log.error(">> clientes-service: ALERTA ALTA {} sobre la cuenta {} - {}",
                    evento.tipoAlerta(), evento.cuentaId(), evento.detalle());
        } else {
            log.warn(">> clientes-service: alerta {} ({}) sobre la cuenta {} - {}",
                    evento.tipoAlerta(), evento.severidad(), evento.cuentaId(), evento.detalle());
        }
    }
}
