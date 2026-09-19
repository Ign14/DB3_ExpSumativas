package cl.duoc.bancoxyz.bff.common.exception;

/**
 * Se lanza cuando un servicio core responde 404 para una cuenta. Cada BFF
 * la traduce a la respuesta de error propia de su canal (ver
 * GlobalExceptionHandler en cada modulo bff-*).
 */
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
