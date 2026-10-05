package cl.duoc.bancoxyz.movimientos.mensajeria;

import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.evento.RetiroRealizadoEvento;
import cl.duoc.bancoxyz.movimientos.domain.MovimientoRepositoryEnMemoria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consume los eventos de retiro y los deja en el historial.
 *
 * Aqui esta el valor de haber elegido mensajeria para esta transaccion: el
 * retiro se aprueba en cuentas-service sin esperar a que este servicio este
 * arriba. Si movimientos-service esta caido o reiniciandose, el evento espera en
 * la cola y se procesa al volver; con una llamada HTTP sincrona, el retiro
 * habria fallado por un servicio que no participa en la decision de aprobarlo.
 */
@Component
public class ConsumidorRetiros {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorRetiros.class);

    /**
     * Cuantos identificadores de evento se recuerdan. Tiene que cubrir con holgura
     * la ventana en la que el broker podria reentregar un mensaje; mas alla de eso
     * recordar no aporta nada y solo ocupa memoria.
     */
    private static final int EVENTOS_RECORDADOS = 10_000;

    private final MovimientoRepositoryEnMemoria repositorio;

    /**
     * JMS garantiza "al menos una vez", no "exactamente una vez": si el
     * procesamiento lanza, el broker reentrega el mensaje. Registrar dos veces
     * el mismo retiro falsearia el historial, asi que se recuerda el
     * identificador del evento y el reintento se descarta.
     *
     * El conjunto esta acotado: un {@code LinkedHashMap} en modo acceso que
     * descarta el identificador mas antiguo al pasarse del tope. Sin tope seria
     * una fuga de memoria monotona en un proceso de larga vida, y la unica forma
     * de limpiarlo seria reiniciar, que es justamente lo que borra la idempotencia
     * entera.
     *
     * Con el broker sin persistencia y un solo consumidor, esto alcanza. Con
     * varias instancias del servicio habria que llevarlo a un almacen compartido,
     * porque cada instancia solo conoce lo que ella vio.
     */
    private final Set<String> eventosProcesados = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> masAntiguo) {
                    return size() > EVENTOS_RECORDADOS;
                }
            }));

    public ConsumidorRetiros(MovimientoRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    @JmsListener(destination = RetiroRealizadoEvento.COLA)
    public void recibir(RetiroRealizadoEvento evento) {
        if (evento == null || evento.eventoId() == null) {
            log.warn(">> movimientos-service: evento descartado por venir sin identificador");
            return;
        }
        if (!eventosProcesados.add(evento.eventoId())) {
            log.info(">> movimientos-service: evento {} ya estaba procesado, se descarta la reentrega",
                    evento.eventoId());
            return;
        }

        // El identificador se marca antes de procesar, asi que si algo falla hay
        // que liberarlo: de lo contrario el broker reentregaria el mensaje, el
        // guard lo descartaria como "ya procesado" y un retiro real desapareceria
        // del historial sin dejar rastro del error.
        try {
            procesar(evento);
        } catch (RuntimeException | Error ex) {
            eventosProcesados.remove(evento.eventoId());
            throw ex;
        }
    }

    private void procesar(RetiroRealizadoEvento evento) {
        MovimientoDTO movimiento = new MovimientoDTO(
                evento.cuentaId(),
                evento.fecha(),
                "retiro",
                evento.monto(),
                "Retiro por canal " + evento.canal());

        Optional<MovimientoDTO> registrado = repositorio.registrar(movimiento);
        if (registrado.isPresent()) {
            log.info(">> movimientos-service: evento {} consumido, retiro de {} agregado al historial de la cuenta {}",
                    evento.eventoId(), evento.monto(), evento.cuentaId());
        } else {
            // No se relanza: el evento volveria a entregarse eternamente por un
            // dato que nunca va a mejorar. Queda en el log para revisarlo, y se
            // libera el identificador para que una reposicion corregida si pueda
            // procesarse.
            eventosProcesados.remove(evento.eventoId());
            log.error(">> movimientos-service: evento {} rechazado por no cumplir las reglas del dominio "
                            + "(cuenta {}, monto {}, fecha {})",
                    evento.eventoId(), evento.cuentaId(), evento.monto(), evento.fecha());
        }
    }
}
