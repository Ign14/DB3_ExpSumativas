package cl.duoc.bancoxyz.bff.cajero.dto;

import java.math.BigDecimal;

/**
 * Comprobante de retiro. {@code movimientoRegistrado} indica si el retiro
 * quedó además anotado en el historial de la cuenta.
 */
public record RetiroResponse(
        Long cuentaId,
        BigDecimal montoSolicitado,
        BigDecimal saldoResultante,
        boolean aprobado,
        String motivoRechazo,
        String fechaMovimiento,
        boolean movimientoRegistrado
) {

    public static RetiroResponse rechazado(Long cuentaId, BigDecimal monto, String motivo, BigDecimal saldo) {
        return new RetiroResponse(cuentaId, monto, saldo, false, motivo, null, false);
    }

    public static RetiroResponse aprobado(Long cuentaId, BigDecimal monto, BigDecimal saldoResultante,
                                          String fechaMovimiento, boolean movimientoRegistrado) {
        return new RetiroResponse(cuentaId, monto, saldoResultante, true, null, fechaMovimiento, movimientoRegistrado);
    }
}
