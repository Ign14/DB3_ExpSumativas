package cl.duoc.bancoxyz.bff.common.exception;

/**
 * Parametro de entrada fuera de rango o mal formado. Se traduce a HTTP 400
 * en {@code BffExceptionHandler}, en vez de dejar que la excepcion escape
 * como un 500.
 */
public class ParametroInvalidoException extends RuntimeException {

    public ParametroInvalidoException(String mensaje) {
        super(mensaje);
    }
}
