package cl.duoc.bancoxyz.bff.cajero.dto;

import java.math.BigDecimal;

/** Lo único que necesita mostrar la pantalla de un cajero: ni nombre, ni edad, ni tipo de cuenta. */
public record SaldoCajeroResponse(Long cuentaId, BigDecimal saldoDisponible) {
}
