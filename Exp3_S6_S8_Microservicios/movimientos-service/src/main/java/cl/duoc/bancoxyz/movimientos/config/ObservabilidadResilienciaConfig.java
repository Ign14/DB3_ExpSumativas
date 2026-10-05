package cl.duoc.bancoxyz.movimientos.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Deja en el log del servicio lo que hacen las politicas de resiliencia.
 *
 * Los endpoints de Actuator muestran el estado actual, pero no sirven para
 * reconstruir despues lo que paso: hay que estar consultandolos en el momento. Un
 * reintento y una transicion del circuito son justo las cosas que uno quiere
 * encontrar en el log cuando revisa un incidente al dia siguiente, y por eso se
 * registran aqui.
 */
@Configuration
public class ObservabilidadResilienciaConfig {

    private static final Logger log = LoggerFactory.getLogger(ObservabilidadResilienciaConfig.class);
    private static final String INSTANCIA = "cuentas";

    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ObservabilidadResilienciaConfig(RetryRegistry retryRegistry,
                                           CircuitBreakerRegistry circuitBreakerRegistry) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
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
                .onStateTransition(evento -> log.warn(
                        ">> resiliencia: CIRCUITO {} paso de {} a {}",
                        INSTANCIA,
                        evento.getStateTransition().getFromState(),
                        evento.getStateTransition().getToState()));

        log.info(">> movimientos-service: eventos de resiliencia de la instancia '{}' registrados en el log",
                INSTANCIA);
    }
}
