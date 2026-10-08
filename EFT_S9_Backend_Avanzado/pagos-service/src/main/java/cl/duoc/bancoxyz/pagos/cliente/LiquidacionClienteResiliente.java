package cl.duoc.bancoxyz.pagos.cliente;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Las dos primitivas de liquidacion, con reintento y circuit breaker pero
 * deliberadamente sin fallback.
 *
 * Esa ausencia es la decision importante de esta clase. En la consulta de la
 * ficha, el fallback tiene sentido: se entrega el historial sin los datos de
 * la cuenta y se marca como degradado, porque una respuesta incompleta sigue
 * siendo util y el cliente puede notar la diferencia. Aqui no existe el
 * equivalente. Un cargo que no se pudo aplicar no tiene version degradada: o el
 * dinero se movio o no se movio, y devolver "aprobado en modo degradado" seria
 * decirle al cliente que su transferencia salio cuando el saldo no cambio. La
 * excepcion tiene que subir para que quien orquesta la operacion la compense o
 * la rechace.
 *
 * Comparten la instancia "cuentas" del circuit breaker con la consulta, a
 * proposito: es la misma dependencia. Si cuentas-service esta caido, no hay
 * razon para que las liquidaciones sigan intentando mientras las consultas ya
 * dejaron de hacerlo.
 *
 * Tampoco llevan TimeLimiter. El TimeLimiter de Resilience4j solo opera sobre
 * llamadas asincronas, y volver estas dos asincronas para poder acotarlas no
 * aportaria nada: el tiempo ya esta acotado por los timeouts del cliente HTTP
 * (1 s de conexion + 1 s de lectura), que es lo que de verdad corta la espera.
 */
@Component
public class LiquidacionClienteResiliente {

    private final LiquidacionGateway gateway;

    public LiquidacionClienteResiliente(LiquidacionGateway gateway) {
        this.gateway = gateway;
    }

    @Retry(name = "cuentas")
    @CircuitBreaker(name = "cuentas")
    public ResultadoLiquidacion cargar(Long cuentaId, BigDecimal monto) {
        return gateway.cargar(cuentaId, monto);
    }

    /**
     * El abono no se reintenta y la razon es la misma por la que el cargo si:
     * reintentar una operacion que mueve dinero solo es seguro cuando repetirla
     * es inofensivo. Un cargo que fallo por no haber llegado nunca al servicio
     * se puede repetir; uno que fallo despues de aplicarse, no, y el cliente no
     * puede distinguir los dos casos. Para el cargo ese riesgo se asume porque
     * va al principio de la operacion y un cargo duplicado se detecta por el
     * saldo; para el abono se evita, porque un abono duplicado regala dinero.
     *
     * La forma correcta de resolverlo de verdad es una clave de idempotencia por
     * operacion que cuentas-service recuerde, y esta anotada como el siguiente
     * paso en el informe tecnico.
     */
    @CircuitBreaker(name = "cuentas")
    public BigDecimal abonar(Long cuentaId, BigDecimal monto) {
        return gateway.abonar(cuentaId, monto);
    }
}
