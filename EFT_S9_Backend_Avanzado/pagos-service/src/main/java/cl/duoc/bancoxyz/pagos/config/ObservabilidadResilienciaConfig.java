package cl.duoc.bancoxyz.pagos.config;

import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.pagos.mensajeria.PublicadorEventosKafka;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Deja en el log del servicio lo que hacen las politicas de resiliencia.
 *
 * Los endpoints de Actuator muestran el estado actual, pero no sirven para
 * reconstruir despues lo que paso: hay que estar consultandolos en el momento. Un
 * reintento y una transicion del circuito son justo las cosas que uno quiere
 * encontrar en el log cuando revisa un incidente al dia siguiente, y por eso se
 * registran aqui.
 *
 * Ademas, la apertura del circuito sale al topico de alertas. Es la union de los
 * dos requerimientos que de otro modo quedarian en compartimentos separados: la
 * tolerancia a fallos deja de ser un asunto interno del servicio y se convierte
 * en un hecho que otros pueden consumir. La apertura se publica y el cierre
 * tambien, con severidades distintas, porque "una dependencia se cayo" y "una
 * dependencia volvio" son las dos cosas que alguien de operaciones necesita
 * saber, y la segunda es la que permite cerrar el incidente.
 */
@Configuration
public class ObservabilidadResilienciaConfig {

    private static final Logger log = LoggerFactory.getLogger(ObservabilidadResilienciaConfig.class);
    private static final String INSTANCIA = "cuentas";

    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final PublicadorEventosKafka publicador;

    public ObservabilidadResilienciaConfig(RetryRegistry retryRegistry,
                                           CircuitBreakerRegistry circuitBreakerRegistry,
                                           PublicadorEventosKafka publicador) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.publicador = publicador;
    }

    @PostConstruct
    void registrarEventos() {
        retryRegistry.retry(INSTANCIA).getEventPublisher()
                .onRetry(evento -> log.warn(
                        ">> resiliencia: REINTENTO {} hacia {} tras {} - {}",
                        evento.getNumberOfRetryAttempts(), INSTANCIA,
                        evento.getLastThrowable() == null
                                ? "sin excepcion"
                                : evento.getLastThrowable().getClass().getSimpleName(),
                        evento.getLastThrowable() == null ? "" : evento.getLastThrowable().getMessage()))
                .onError(evento -> log.warn(
                        ">> resiliencia: se agotaron los {} intentos hacia {}",
                        evento.getNumberOfRetryAttempts(), INSTANCIA));

        circuitBreakerRegistry.circuitBreaker(INSTANCIA).getEventPublisher()
                .onStateTransition(evento -> {
                    CircuitBreaker.State destino = evento.getStateTransition().getToState();
                    log.warn(">> resiliencia: CIRCUITO {} paso de {} a {}",
                            INSTANCIA,
                            evento.getStateTransition().getFromState(),
                            destino);
                    // Solo las dos transiciones que importan afuera. Publicar
                    // tambien el paso a HALF_OPEN llenaria el topico de ruido:
                    // es un paso intermedio automatico que no describe un cambio
                    // en la salud de la dependencia, solo que el circuito va a
                    // probar de nuevo.
                    if (destino == CircuitBreaker.State.OPEN) {
                        publicarAlerta(AlertaSeguridadEvento.SEVERIDAD_ALTA,
                                "El circuito hacia " + INSTANCIA + " se abrio: las llamadas se "
                                        + "responden degradadas sin intentar la dependencia.");
                    } else if (destino == CircuitBreaker.State.CLOSED) {
                        publicarAlerta(AlertaSeguridadEvento.SEVERIDAD_INFO,
                                "El circuito hacia " + INSTANCIA + " se cerro: la dependencia "
                                        + "volvio a responder y el trafico es normal.");
                    }
                });

        log.info(">> pagos-service: eventos de resiliencia de la instancia '{}' registrados en el log "
                + "y las transiciones del circuito publicadas en el topico {}",
                INSTANCIA, AlertaSeguridadEvento.TOPICO);
    }

    /**
     * La alerta va sin numero de cuenta y no es un olvido: un circuito abierto
     * no es un problema de una cuenta en particular, es un problema de la
     * dependencia. Poner aqui la cuenta de la peticion que tuvo la mala suerte
     * de ser la ultima solo haria que el consumidor le atribuyera a ese cliente
     * una alerta que no le corresponde.
     */
    private void publicarAlerta(String severidad, String detalle) {
        publicador.publicarAlerta(new AlertaSeguridadEvento(
                UUID.randomUUID().toString(),
                AlertaSeguridadEvento.DEPENDENCIA_DEGRADADA,
                null,
                detalle,
                severidad,
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)));
    }
}
