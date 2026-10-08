package cl.duoc.bancoxyz.pagos.cliente;

import java.math.BigDecimal;

/**
 * Resultado de pedirle a cuentas-service que cargue o abone un monto.
 *
 * Igual que {@link ResultadoCuenta}, distingue el rechazo de la falla. "No
 * habia saldo" es una respuesta correcta del servicio y no debe contar como
 * fallo del circuit breaker; "no respondio" es una excepcion y no llega aqui.
 */
public record ResultadoLiquidacion(boolean aplicado, BigDecimal saldoResultante, String motivoRechazo) {

    public static ResultadoLiquidacion aplicada(BigDecimal saldoResultante) {
        return new ResultadoLiquidacion(true, saldoResultante, null);
    }

    public static ResultadoLiquidacion rechazada(String motivo) {
        return new ResultadoLiquidacion(false, null, motivo);
    }
}
