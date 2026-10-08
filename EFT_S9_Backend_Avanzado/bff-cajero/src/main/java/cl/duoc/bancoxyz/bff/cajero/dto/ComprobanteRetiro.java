package cl.duoc.bancoxyz.bff.cajero.dto;

import java.math.BigDecimal;

/**
 * Comprobante que el cajero le entrega al titular.
 *
 * Se llama asi y no RetiroResponse para no competir con el record del mismo
 * nombre de common-dominio, que es la respuesta que devuelve cuentas-service.
 * Son dos cosas distintas y conviene que el codigo lo diga: una es lo que el
 * dominio responde, y esta es lo que el canal decide mostrar, que incluye la
 * fecha del movimiento y si el retiro alcanzara a aparecer en el historial.
 */
public record ComprobanteRetiro(
        Long cuentaId,
        BigDecimal montoSolicitado,
        BigDecimal saldoResultante,
        boolean aprobado,
        String motivoRechazo,
        String fechaMovimiento,
        boolean movimientoRegistrado
) {

    public static ComprobanteRetiro rechazado(Long cuentaId, BigDecimal monto, String motivo, BigDecimal saldo) {
        return new ComprobanteRetiro(cuentaId, monto, saldo, false, motivo, null, false);
    }

    public static ComprobanteRetiro aprobado(Long cuentaId, BigDecimal monto, BigDecimal saldoResultante,
                                          String fechaMovimiento, boolean movimientoRegistrado) {
        return new ComprobanteRetiro(cuentaId, monto, saldoResultante, true, null, fechaMovimiento, movimientoRegistrado);
    }
}
