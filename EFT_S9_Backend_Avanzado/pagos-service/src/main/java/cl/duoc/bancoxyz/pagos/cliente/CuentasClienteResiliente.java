package cl.duoc.bancoxyz.pagos.cliente;

import cl.duoc.bancoxyz.common.excepcion.LlamadaRechazadaException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Envuelve la llamada a cuentas-service con las tres politicas de Resilience4j.
 *
 * El orden en que se aplican no es casual. Resilience4j envuelve de afuera
 * hacia adentro como Retry, CircuitBreaker, TimeLimiter, asi que el metodo de
 * fallback va declarado en {@code @Retry}, que es la capa mas externa. Si
 * estuviera en {@code @CircuitBreaker}, el primer fallo se convertiria en un
 * valor de retorno valido, Retry veria exito y no reintentaria nunca: el
 * fallback tiene que ser lo ultimo que ocurre, no lo primero.
 *
 * Por que cada politica:
 *
 * - {@code TimeLimiter} acota cuanto espera el cliente. Un servicio que acepta
 *   la conexion y nunca contesta es peor que uno caido, porque consume hilos sin
 *   dar senales de fallo.
 * - {@code Retry} cubre el fallo transitorio: un reinicio, un paquete perdido.
 *   Un solo reintento, 200 ms despues. Un tercer intento contra un servicio caido
 *   agrega mas de dos segundos a la espera del usuario para muy poca probabilidad
 *   extra de exito, y el fallo sostenido no lo resuelve insistir sino el circuito.
 * - {@code CircuitBreaker} cubre el fallo sostenido. Cuando la mitad de las
 *   llamadas de la ventana falla, deja de intentar y responde de inmediato con
 *   el fallback. Eso protege a las dos partes: a nosotros de acumular esperas, y
 *   a cuentas-service de recibir trafico mientras arranca.
 *
 * Solo se reintenta y se cuenta como fallo lo que es un fallo de verdad: la
 * configuracion central registra ServicioNoDisponibleException y
 * TimeoutException, deja fuera CallNotPermittedException para no reintentar
 * contra un circuito ya abierto, y deja fuera LlamadaRechazadaException porque un
 * 401 es un problema de configuracion que no mejora reintentando.
 */
@Component
public class CuentasClienteResiliente {

    private static final Logger log = LoggerFactory.getLogger(CuentasClienteResiliente.class);

    private final CuentasGateway gateway;
    private final Executor ejecutor;

    public CuentasClienteResiliente(CuentasGateway gateway,
                                    @Qualifier("ejecutorCuentas") Executor ejecutor) {
        this.gateway = gateway;
        this.ejecutor = ejecutor;
    }

    /**
     * El executor se pasa explicitamente y no se usa el de por defecto.
     *
     * {@code supplyAsync} sin executor corre en el ForkJoinPool comun, cuyo
     * paralelismo es el numero de nucleos menos uno y que esta pensado para
     * trabajo de CPU, no para esperar en un socket. Y hay un detalle que lo
     * vuelve grave: el TimeLimiter cancela el futuro a los 2 segundos, pero
     * cancelar un {@code CompletableFuture} no interrumpe el hilo que ya esta
     * ejecutando la tarea, asi que la llamada bloqueada sigue ocupando su hilo
     * hasta que el socket se rinda. Con el pool comun, unas pocas llamadas a un
     * servicio colgado agotan el paralelismo del proceso completo y el circuito
     * se abre por inanicion del pool, no porque la dependencia este caida. Con
     * un pool propio y acotado, el dano queda contenido en estas llamadas.
     */
    @Retry(name = "cuentas", fallbackMethod = "cuentaDegradada")
    @CircuitBreaker(name = "cuentas")
    @TimeLimiter(name = "cuentas")
    public CompletableFuture<ResultadoCuenta> obtenerCuenta(Long cuentaId) {
        return CompletableFuture.supplyAsync(() -> gateway.obtenerCuenta(cuentaId), ejecutor);
    }

    /**
     * Respuesta degradada: se entrega el historial sin los datos de la cuenta,
     * marcado como degradado, en vez de fallar la peticion completa. Para quien
     * consulta movimientos, el nombre del titular es un adorno; el historial es
     * el dato que vino a buscar.
     *
     * El nivel del log distingue los dos casos, porque exigen reacciones
     * distintas: una dependencia caida se espera y se recupera sola, mientras que
     * una llamada rechazada necesita que alguien revise la configuracion.
     */
    @SuppressWarnings("unused")
    private CompletableFuture<ResultadoCuenta> cuentaDegradada(Long cuentaId, Throwable causa) {
        if (causa instanceof LlamadaRechazadaException rechazo) {
            log.error(">> pagos-service: cuentas-service RECHAZO la llamada de la cuenta {} con HTTP {} - "
                            + "esto no se arregla solo, revise las credenciales del cliente OAuth2 y el issuer: {}",
                    cuentaId, rechazo.codigoHttp(), rechazo.getMessage());
        } else {
            log.warn(">> pagos-service: cuenta {} servida en modo degradado por {}: {}",
                    cuentaId, causa.getClass().getSimpleName(), causa.getMessage());
        }
        return CompletableFuture.completedFuture(ResultadoCuenta.degradado());
    }
}
