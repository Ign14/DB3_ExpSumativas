package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/** Datos maestros de una cuenta, tal como los expone core-cuentas-service. */
public record CuentaDTO(
        Long cuentaId,
        String nombreTitular,
        Integer edad,
        String tipoCuenta,
        BigDecimal saldo
) {
}
