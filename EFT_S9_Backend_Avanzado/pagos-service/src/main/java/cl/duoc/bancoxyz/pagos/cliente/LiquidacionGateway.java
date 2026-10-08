package cl.duoc.bancoxyz.pagos.cliente;

import java.math.BigDecimal;

/**
 * Las llamadas HTTP crudas que mueven saldo en cuentas-service, sin politicas
 * de resiliencia.
 *
 * Separada de {@link CuentasGateway} porque lo que se puede hacer cuando una de
 * estas llamadas falla no se parece en nada a lo que se puede hacer cuando falla
 * una lectura: una consulta se degrada, un movimiento de dinero se compensa o se
 * rechaza.
 */
public interface LiquidacionGateway {

    /**
     * Carga un monto a la cuenta.
     *
     * @return aplicada con el saldo resultante, o rechazada si no habia fondos.
     * @throws cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException
     *         si la cuenta no existe.
     * @throws cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException
     *         si cuentas-service no responde o responde 5xx.
     */
    ResultadoLiquidacion cargar(Long cuentaId, BigDecimal monto);

    /**
     * Abona un monto a la cuenta. Un abono no se rechaza por saldo: o se aplica,
     * o la llamada falla.
     *
     * @throws cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException
     *         si la cuenta no existe.
     * @throws cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException
     *         si cuentas-service no responde o responde 5xx.
     */
    BigDecimal abonar(Long cuentaId, BigDecimal monto);
}
