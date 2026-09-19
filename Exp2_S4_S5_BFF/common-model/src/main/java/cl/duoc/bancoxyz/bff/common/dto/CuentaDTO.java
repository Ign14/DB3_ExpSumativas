package cl.duoc.bancoxyz.bff.common.dto;

import java.math.BigDecimal;

/**
 * Datos maestros de una cuenta (titular, tipo y saldo), tal como los expone
 * core-cuentas-service. Es el contrato "completo": cada BFF decide, en su
 * propia capa de transformacion, cuanto de esto reenvia a su canal.
 */
public record CuentaDTO(
        Long cuentaId,
        String nombreTitular,
        Integer edad,
        String tipoCuenta,
        BigDecimal saldo
) {
}
