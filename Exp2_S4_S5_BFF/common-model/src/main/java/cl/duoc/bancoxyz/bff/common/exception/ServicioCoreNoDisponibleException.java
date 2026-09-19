package cl.duoc.bancoxyz.bff.common.exception;

/**
 * Se lanza cuando un servicio core no responde o responde con un error que
 * no es "cuenta no encontrada" (timeout, 5xx, conexion rechazada). Permite a
 * cada BFF devolver un 502/503 propio en vez de una excepcion generica.
 */
public class ServicioCoreNoDisponibleException extends RuntimeException {

    public ServicioCoreNoDisponibleException(String servicio, Throwable causa) {
        super("El servicio core '" + servicio + "' no respondio correctamente: " + causa.getMessage(), causa);
    }

    public ServicioCoreNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
