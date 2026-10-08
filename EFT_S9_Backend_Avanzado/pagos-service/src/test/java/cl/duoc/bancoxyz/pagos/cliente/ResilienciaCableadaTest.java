package cl.duoc.bancoxyz.pagos.cliente;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.excepcion.LlamadaRechazadaException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprueba que las anotaciones de Resilience4j esten realmente cableadas sobre
 * {@link CuentasClienteResiliente}: que los aspectos se apliquen, que la
 * configuracion se enlace desde properties y, sobre todo, que el metodo de
 * fallback se invoque.
 *
 * Hace falta porque las anotaciones son inertes sin un contexto de Spring: un
 * test que construya la clase con {@code new} ejercita el metodo pelado y pasaria
 * igual aunque alguien borrara las tres anotaciones. Y el error mas probable en
 * esta configuracion es silencioso: mover el {@code fallbackMethod} de
 * {@code @Retry} a {@code @CircuitBreaker} no rompe nada visible, solo desactiva
 * el reintento.
 *
 * Se levanta solo lo necesario —AOP y las tres autoconfiguraciones de
 * Resilience4j— en vez de la aplicacion completa, que exigiria Config Server,
 * Eureka y el auth-server arriba.
 */
class ResilienciaCableadaTest {

    private static final CuentaDTO CUENTA =
            new CuentaDTO(103L, "Bob Johnson", 30, "ahorro", new BigDecimal("7000"));

    /**
     * Los valores de pagos-service.yml en el Config Server, con una sola
     * diferencia deliberada: la espera entre reintentos baja de 200 ms a 50 ms para
     * no alargar la suite. Lo que se verifica aqui es que los aspectos esten
     * cableados, no cuanto esperan.
     */
    private static final String[] PROPIEDADES = {
            "resilience4j.circuitbreaker.instances.cuentas.sliding-window-type=COUNT_BASED",
            "resilience4j.circuitbreaker.instances.cuentas.sliding-window-size=10",
            "resilience4j.circuitbreaker.instances.cuentas.minimum-number-of-calls=4",
            "resilience4j.circuitbreaker.instances.cuentas.failure-rate-threshold=50",
            "resilience4j.circuitbreaker.instances.cuentas.wait-duration-in-open-state=10s",
            "resilience4j.circuitbreaker.instances.cuentas.record-exceptions[0]="
                    + "cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException",
            "resilience4j.circuitbreaker.instances.cuentas.record-exceptions[1]="
                    + "java.util.concurrent.TimeoutException",
            "resilience4j.retry.instances.cuentas.max-attempts=2",
            "resilience4j.retry.instances.cuentas.wait-duration=50ms",
            "resilience4j.retry.instances.cuentas.retry-exceptions[0]="
                    + "cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException",
            "resilience4j.retry.instances.cuentas.retry-exceptions[1]="
                    + "java.util.concurrent.TimeoutException",
            "resilience4j.retry.instances.cuentas.ignore-exceptions[0]="
                    + "io.github.resilience4j.circuitbreaker.CallNotPermittedException",
            "resilience4j.retry.instances.cuentas.ignore-exceptions[1]="
                    + "cl.duoc.bancoxyz.common.excepcion.LlamadaRechazadaException",
            "resilience4j.timelimiter.instances.cuentas.timeout-duration=2s",
            "resilience4j.timelimiter.instances.cuentas.cancel-running-future=true"
    };

    /** Doble de la capa HTTP que se puede cambiar de comportamiento por test. */
    static class GatewayControlable implements CuentasGateway {
        final AtomicInteger llamadas = new AtomicInteger();
        final AtomicReference<RuntimeException> falla = new AtomicReference<>();
        final AtomicReference<ResultadoCuenta> respuesta =
                new AtomicReference<>(ResultadoCuenta.encontrada(CUENTA));

        @Override
        public ResultadoCuenta obtenerCuenta(Long cuentaId) {
            llamadas.incrementAndGet();
            RuntimeException ex = falla.get();
            if (ex != null) {
                throw ex;
            }
            return respuesta.get();
        }
    }

    @Configuration
    static class ConfiguracionDePrueba {
        @Bean
        GatewayControlable gateway() {
            return new GatewayControlable();
        }

        @Bean("ejecutorCuentas")
        Executor ejecutorCuentas() {
            return java.util.concurrent.Executors.newFixedThreadPool(4);
        }

        @Bean
        CuentasClienteResiliente cliente(GatewayControlable gateway,
                                         @org.springframework.beans.factory.annotation.Qualifier("ejecutorCuentas")
                                         Executor ejecutor) {
            return new CuentasClienteResiliente(gateway, ejecutor);
        }
    }

    private ApplicationContextRunner contexto() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class,
                        CircuitBreakerAutoConfiguration.class,
                        RetryAutoConfiguration.class,
                        TimeLimiterAutoConfiguration.class))
                .withUserConfiguration(ConfiguracionDePrueba.class)
                .withPropertyValues(PROPIEDADES);
    }

    @Test
    @DisplayName("El contexto arranca y la instancia 'cuentas' se enlaza desde la configuracion")
    void configuracionEnlazada() {
        contexto().run(ctx -> {
            CircuitBreakerRegistry registro = ctx.getBean(CircuitBreakerRegistry.class);
            CircuitBreaker breaker = registro.circuitBreaker("cuentas");
            assertEquals(10, breaker.getCircuitBreakerConfig().getSlidingWindowSize());
            assertEquals(4, breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls());
            assertEquals(50.0f, breaker.getCircuitBreakerConfig().getFailureRateThreshold());
        });
    }

    @Test
    @DisplayName("El bean esta envuelto por los aspectos: no es la instancia pelada")
    void aspectosAplicados() {
        contexto().run(ctx -> {
            CuentasClienteResiliente cliente = ctx.getBean(CuentasClienteResiliente.class);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(cliente),
                    "sin proxy, las anotaciones de Resilience4j no hacen nada");
        });
    }

    @Test
    @DisplayName("Con la dependencia sana, la llamada pasa y no interviene nada")
    void caminoFeliz() {
        contexto().run(ctx -> {
            GatewayControlable gateway = ctx.getBean(GatewayControlable.class);
            CuentasClienteResiliente cliente = ctx.getBean(CuentasClienteResiliente.class);

            ResultadoCuenta resultado = cliente.obtenerCuenta(103L).join();

            assertTrue(resultado.disponible());
            assertSame(CUENTA, resultado.cuenta());
            assertEquals(1, gateway.llamadas.get());
        });
    }

    /**
     * El test que justifica que el {@code fallbackMethod} este en {@code @Retry} y
     * no en {@code @CircuitBreaker}: con el fallback en la capa interna, el primer
     * fallo se convertiria en un valor valido y el reintento no ocurriria nunca.
     * Aqui se exige que haya ocurrido Y que el fallback haya respondido.
     */
    @Test
    @DisplayName("Con la dependencia caida: reintenta y despues entrega la respuesta degradada")
    void reintentaYLuegoDegrada() {
        contexto().run(ctx -> {
            GatewayControlable gateway = ctx.getBean(GatewayControlable.class);
            gateway.falla.set(new ServicioNoDisponibleException("cuentas-service no responde"));
            CuentasClienteResiliente cliente = ctx.getBean(CuentasClienteResiliente.class);

            ResultadoCuenta resultado = cliente.obtenerCuenta(103L).join();

            assertEquals(ResultadoCuenta.Origen.DEGRADADO, resultado.origen(),
                    "el fallback tiene que responder, no propagar la excepcion");
            assertEquals(2, gateway.llamadas.get(),
                    "max-attempts=2: el reintento debe ocurrir antes del fallback");
        });
    }

    @Test
    @DisplayName("Una llamada rechazada va directo al fallback, sin reintentar")
    void rechazoNoReintenta() {
        contexto().run(ctx -> {
            GatewayControlable gateway = ctx.getBean(GatewayControlable.class);
            gateway.falla.set(new LlamadaRechazadaException("401", 401));
            CuentasClienteResiliente cliente = ctx.getBean(CuentasClienteResiliente.class);

            ResultadoCuenta resultado = cliente.obtenerCuenta(103L).join();

            assertEquals(ResultadoCuenta.Origen.DEGRADADO, resultado.origen());
            assertEquals(1, gateway.llamadas.get(), "un 401 no mejora reintentando");
        });
    }

    @Test
    @DisplayName("Con el circuito abierto, las llamadas ya no llegan a la dependencia")
    void circuitoAbiertoCortaElTrafico() {
        contexto().run(ctx -> {
            GatewayControlable gateway = ctx.getBean(GatewayControlable.class);
            gateway.falla.set(new ServicioNoDisponibleException("caido"));
            CuentasClienteResiliente cliente = ctx.getBean(CuentasClienteResiliente.class);

            // 2 peticiones x 2 intentos = 4 llamadas, el minimo de la ventana.
            cliente.obtenerCuenta(103L).join();
            cliente.obtenerCuenta(103L).join();
            assertEquals(CircuitBreaker.State.OPEN,
                    ctx.getBean(CircuitBreakerRegistry.class).circuitBreaker("cuentas").getState());

            int llamadasAntes = gateway.llamadas.get();
            ResultadoCuenta resultado = cliente.obtenerCuenta(103L).join();

            assertEquals(ResultadoCuenta.Origen.DEGRADADO, resultado.origen());
            assertEquals(llamadasAntes, gateway.llamadas.get(),
                    "con el circuito abierto no debe salir ninguna llamada mas");
        });
    }

    @Test
    @DisplayName("Un 404 no abre el circuito aunque se repita muchas veces")
    void cuentaInexistenteNoAbreElCircuito() {
        contexto().run(ctx -> {
            CuentasClienteResiliente cliente = ctx.getBean(CuentasClienteResiliente.class);
            GatewayControlable gateway = ctx.getBean(GatewayControlable.class);
            // El gateway real devuelve NO_ENCONTRADA sin lanzar ante un 404 (eso lo
            // verifica CuentasGatewayHttpTest contra un servidor de verdad); aqui
            // se reproduce ese contrato para ver el efecto sobre el circuito.
            gateway.respuesta.set(ResultadoCuenta.noEncontrada());
            CircuitBreaker breaker = ctx.getBean(CircuitBreakerRegistry.class).circuitBreaker("cuentas");

            for (int i = 0; i < 20; i++) {
                ResultadoCuenta resultado = cliente.obtenerCuenta(999_999L).join();
                assertEquals(ResultadoCuenta.Origen.NO_ENCONTRADA, resultado.origen());
                assertFalse(resultado.disponible());
            }

            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
            assertEquals(20, gateway.llamadas.get(), "las 20 llamadas tienen que haber salido");
            assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
        });
    }
}
