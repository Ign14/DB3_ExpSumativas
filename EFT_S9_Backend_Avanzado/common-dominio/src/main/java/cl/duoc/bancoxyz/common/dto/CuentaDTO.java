package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/** Datos de una cuenta, tal como los expone cuentas-service. */
public record CuentaDTO(
        Long cuentaId,
        String nombreTitular,
        Integer edad,
        String tipoCuenta,
        BigDecimal saldo
) {
}
