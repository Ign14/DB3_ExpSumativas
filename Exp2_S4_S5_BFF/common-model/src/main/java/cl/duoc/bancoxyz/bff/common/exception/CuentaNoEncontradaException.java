package cl.duoc.bancoxyz.bff.common.exception;

/** La cuenta solicitada no existe en core-cuentas-service. Se traduce a HTTP 404. */
public class CuentaNoEncontradaException extends RuntimeException {

    private final Long cuentaId;

    public CuentaNoEncontradaException(Long cuentaId) {
        super("No existe la cuenta " + cuentaId);
        this.cuentaId = cuentaId;
    }

    public Long getCuentaId() {
        return cuentaId;
    }
}
