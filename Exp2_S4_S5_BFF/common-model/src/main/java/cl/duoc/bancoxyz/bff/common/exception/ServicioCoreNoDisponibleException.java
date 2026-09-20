package cl.duoc.bancoxyz.bff.common.exception;

/**
 * Un servicio core no respondió, o respondió con un error que no es "cuenta no
 * encontrada". Se traduce a HTTP 502: el fallo es del backend, no del cliente
 * del BFF.
 */
public class ServicioCoreNoDisponibleException extends RuntimeException {

    public ServicioCoreNoDisponibleException(String servicio, Throwable causa) {
        super("El servicio core '" + servicio + "' no respondió correctamente: " + causa.getMessage(), causa);
    }

    public ServicioCoreNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
