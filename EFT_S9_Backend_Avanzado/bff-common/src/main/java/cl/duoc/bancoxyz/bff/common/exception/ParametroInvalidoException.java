package cl.duoc.bancoxyz.bff.common.exception;

/** Parámetro de entrada fuera de rango o mal formado. Se traduce a HTTP 400. */
public class ParametroInvalidoException extends RuntimeException {

    public ParametroInvalidoException(String mensaje) {
        super(mensaje);
    }
}
