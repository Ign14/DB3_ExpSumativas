package cl.duoc.bancoxyz.bff.cajero.dto;

import java.math.BigDecimal;

/** Lo minimo que un cajero necesita mostrar: nada de nombre, edad ni tipo de cuenta. */
public record SaldoCajeroResponse(Long cuentaId, BigDecimal saldoDisponible) {
}
