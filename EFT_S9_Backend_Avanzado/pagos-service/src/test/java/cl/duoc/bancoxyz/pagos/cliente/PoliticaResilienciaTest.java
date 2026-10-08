package cl.duoc.bancoxyz.pagos.cliente;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.excepcion.LlamadaRechazadaException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica que los valores configurados para la instancia "cuentas" produzcan el
 * comportamiento que se espera de ellos.
 *
 * Los parametros replican los de pagos-service.yml en el Config Server. Si
 * alli se cambian, estos valores tienen que cambiar con ellos:
 *
 *   circuitbreaker: ventana 10, minimo 4 llamadas, umbral 50%, 10 s abierto,
 *                   3 llamadas permitidas en half-open
 *   retry:          2 intentos, espera 200 ms
 *   timelimiter:    2 s
 *
 * Que los aspectos esten activos y lean esa configuracion es otra cosa, y la
 * comprueba {@link ResilienciaCableadaTest}.
 */
class PoliticaResilienciaTest {

    private static final CuentaDTO CUENTA =
            new CuentaDTO(103L, "Bob Johnson", 30, "ahorro", new BigDecimal("7000"));

    private CircuitBreaker circuitBreaker() {
        return circuitBreaker(Duration.ofSeconds(10));
    }

    private CircuitBreaker circuitBreaker(Duration esperaAbierto) {
        return CircuitBreaker.of("cuentas", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(esperaAbierto)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(ServicioNoDisponibleException.class, TimeoutException.class)
                .build());
    }

    private Retry retry() {
        return Retry.of("cuentas", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(ServicioNoDisponibleException.class, TimeoutException.class)
                .ignoreExceptions(CallNotPermittedException.class, LlamadaRechazadaException.class)
                .build());
    }

    @Test
    @DisplayName("Cuatro fallos abren el circuito y la llamada siguiente ya no se permite")
    void circuitoSeAbreConFallosSostenidos() {
        CircuitBreaker breaker = circuitBreaker();
        CuentasGateway caido = cuentaId -> {
            throw new ServicioNoDisponibleException("cuentas-service no responde");
        };

        for (int i = 0; i < 4; i++) {
            assertThrows(ServicioNoDisponibleException.class,
                    () -> breaker.decorateSupplier(() -> caido.obtenerCuenta(103L)).get());
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertThrows(CallNotPermittedException.class,
                () -> breaker.decorateSupplier(() -> caido.obtenerCuenta(103L)).get(),
                "con el circuito abierto la llamada no debe salir a la red");
    }

    /**
     * Un 401 del otro servicio no es una caida: es que nuestro token no sirve.
     * Abrir el circuito por eso disfrazaria un problema de configuracion
     * permanente de caida pasajera, y el log diria "respuesta degradada" cuando lo
     * que hace falta es revisar las credenciales.
     */
    @Test
    @DisplayName("Una llamada rechazada (401) no abre el circuito aunque se repita")
    void llamadaRechazadaNoAbreElCircuito() {
        CircuitBreaker breaker = circuitBreaker();
        CuentasGateway rechaza = cuentaId -> {
            throw new LlamadaRechazadaException("401", 401);
        };

        for (int i = 0; i < 20; i++) {
            assertThrows(LlamadaRechazadaException.class,
                    () -> breaker.decorateSupplier(() -> rechaza.obtenerCuenta(103L)).get());
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    @DisplayName("Un fallo transitorio se reintenta y la segunda llamada ya responde bien")
    void reintentoResuelveFalloTransitorio() {
        AtomicInteger intentos = new AtomicInteger();
        CuentasGateway intermitente = cuentaId -> {
            if (intentos.incrementAndGet() < 2) {
                throw new ServicioNoDisponibleException("reinicio en curso");
            }
            return ResultadoCuenta.encontrada(CUENTA);
        };

        ResultadoCuenta resultado = retry().executeSupplier(() -> intermitente.obtenerCuenta(103L));

        assertTrue(resultado.disponible());
        assertEquals(2, intentos.get(), "debio reintentar una sola vez");
    }

    @Test
    @DisplayName("El reintento se detiene en el segundo intento y no insiste mas")
    void reintentoTieneTope() {
        AtomicInteger intentos = new AtomicInteger();
        CuentasGateway caido = cuentaId -> {
            intentos.incrementAndGet();
            throw new ServicioNoDisponibleException("caido");
        };

        assertThrows(ServicioNoDisponibleException.class,
                () -> retry().executeSupplier(() -> caido.obtenerCuenta(103L)));
        assertEquals(2, intentos.get());
    }

    @Test
    @DisplayName("Una llamada rechazada no gasta reintentos: no va a mejorar")
    void noReintentaLlamadaRechazada() {
        AtomicInteger intentos = new AtomicInteger();
        CuentasGateway rechaza = cuentaId -> {
            intentos.incrementAndGet();
            throw new LlamadaRechazadaException("401", 401);
        };

        assertThrows(LlamadaRechazadaException.class,
                () -> retry().executeSupplier(() -> rechaza.obtenerCuenta(103L)));
        assertEquals(1, intentos.get());
    }

    /**
     * No se reintenta contra un circuito abierto: ya se sabe que va a fallar y
     * cada intento solo suma espera para el cliente.
     */
    @Test
    @DisplayName("Con el circuito abierto no se gastan reintentos")
    void noReintentaCircuitoAbierto() {
        AtomicInteger intentos = new AtomicInteger();

        assertThrows(CallNotPermittedException.class, () -> retry().executeSupplier(() -> {
            intentos.incrementAndGet();
            throw CallNotPermittedException.createCallNotPermittedException(circuitBreaker());
        }));
        assertEquals(1, intentos.get());
    }

    /**
     * La recuperacion automatica es la mitad del valor del circuit breaker: si no
     * se cerrara solo, haria falta reiniciar el servicio despues de cada incidente.
     * Se usa una espera corta en vez de los 10 s configurados para no alargar la
     * suite; lo que se verifica es la transicion, no el valor del reloj.
     */
    @Test
    @DisplayName("Pasada la espera, el circuito prueba de nuevo y se cierra si resulta")
    void circuitoSeRecuperaSolo() throws Exception {
        CircuitBreaker breaker = circuitBreaker(Duration.ofMillis(300));
        CuentasGateway caido = cuentaId -> {
            throw new ServicioNoDisponibleException("caido");
        };
        for (int i = 0; i < 4; i++) {
            assertThrows(ServicioNoDisponibleException.class,
                    () -> breaker.decorateSupplier(() -> caido.obtenerCuenta(103L)).get());
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(500);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState(),
                "la transicion automatica a HALF_OPEN debe ocurrir sin que nadie llame");

        CuentasGateway sano = cuentaId -> ResultadoCuenta.encontrada(CUENTA);
        for (int i = 0; i < 3; i++) {
            assertTrue(breaker.decorateSupplier(() -> sano.obtenerCuenta(103L)).get().disponible());
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    @DisplayName("Si el circuito en half-open vuelve a fallar, se abre otra vez")
    void circuitoSeVuelveAAbrirSiSigueFallando() throws Exception {
        CircuitBreaker breaker = circuitBreaker(Duration.ofMillis(300));
        CuentasGateway caido = cuentaId -> {
            throw new ServicioNoDisponibleException("caido");
        };
        for (int i = 0; i < 4; i++) {
            assertThrows(ServicioNoDisponibleException.class,
                    () -> breaker.decorateSupplier(() -> caido.obtenerCuenta(103L)).get());
        }
        Thread.sleep(500);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        for (int i = 0; i < 3; i++) {
            assertThrows(ServicioNoDisponibleException.class,
                    () -> breaker.decorateSupplier(() -> caido.obtenerCuenta(103L)).get());
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    @DisplayName("Un servicio que acepta la conexion y no responde se corta a los 2 segundos")
    void timeLimiterCortaLaEspera() {
        TimeLimiter limitador = TimeLimiter.of("cuentas", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(2))
                .cancelRunningFuture(true)
                .build());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            long inicio = System.nanoTime();
            assertThrows(TimeoutException.class, () -> limitador.executeFutureSupplier(
                    () -> CompletableFuture.supplyAsync(() -> {
                        try {
                            Thread.sleep(30_000);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return ResultadoCuenta.encontrada(CUENTA);
                    }, pool)));
            long transcurrido = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

            assertTrue(transcurrido >= 1_900 && transcurrido < 6_000,
                    "el corte debio ocurrir cerca de los 2 segundos, no a los " + transcurrido + " ms");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Una llamada que tarda bastante menos que el limite tiene que pasar sin que el
     * TimeLimiter intervenga. El futuro se completa dentro del limitador y no
     * antes, que es lo que hace que la prueba signifique algo.
     */
    @Test
    @DisplayName("Una llamada normal no se ve afectada por el limite de tiempo")
    void timeLimiterNoEstorbaLlamadasRapidas() throws Exception {
        TimeLimiter limitador = TimeLimiter.of("cuentas", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(2))
                .build());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ResultadoCuenta resultado = limitador.executeFutureSupplier(
                    () -> CompletableFuture.supplyAsync(() -> {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return ResultadoCuenta.encontrada(CUENTA);
                    }, pool));

            assertTrue(resultado.disponible());
            assertEquals("Bob Johnson", resultado.cuenta().nombreTitular());
        } finally {
            pool.shutdownNow();
        }
    }
}
