package cl.duoc.bancoxyz.bff.cuentas.domain;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;

import java.math.BigDecimal;

/** Registro interno de una cuenta. Inmutable: un débito produce una instancia nueva. */
public record CuentaRegistro(Long cuentaId, String nombreTitular, int edad, String tipoCuenta, BigDecimal saldo) {

    CuentaRegistro conSaldo(BigDecimal nuevoSaldo) {
        return new CuentaRegistro(cuentaId, nombreTitular, edad, tipoCuenta, nuevoSaldo);
    }

    CuentaDTO aDto() {
        return new CuentaDTO(cuentaId, nombreTitular, edad, tipoCuenta, saldo);
    }
}
