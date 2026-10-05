package cl.duoc.bancoxyz.movimientos.cliente;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;

/**
 * Resultado de consultar una cuenta en el otro microservicio, con el origen del
 * dato explicito.
 *
 * Hace falta distinguir tres situaciones que un {@code Optional} confundiria en
 * una sola: la cuenta existe, la cuenta no existe, y no sabemos si existe porque
 * cuentas-service no respondio. Las dos ultimas se ven igual desde afuera si
 * solo se devuelve "vacio", y no son lo mismo: una es una respuesta correcta y
 * la otra es una degradacion que el cliente debe poder notar.
 */
public record ResultadoCuenta(CuentaDTO cuenta, Origen origen) {

    public enum Origen {
        /** cuentas-service respondio con los datos de la cuenta. */
        SERVICIO,
        /** cuentas-service respondio que la cuenta no existe. */
        NO_ENCONTRADA,
        /** cuentas-service no respondio: esto viene del fallback. */
        DEGRADADO
    }

    public static ResultadoCuenta encontrada(CuentaDTO cuenta) {
        return new ResultadoCuenta(cuenta, Origen.SERVICIO);
    }

    public static ResultadoCuenta noEncontrada() {
        return new ResultadoCuenta(null, Origen.NO_ENCONTRADA);
    }

    public static ResultadoCuenta degradado() {
        return new ResultadoCuenta(null, Origen.DEGRADADO);
    }

    public boolean disponible() {
        return origen == Origen.SERVICIO;
    }
}
