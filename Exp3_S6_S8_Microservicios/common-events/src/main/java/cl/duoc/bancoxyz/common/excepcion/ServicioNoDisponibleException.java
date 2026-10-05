package cl.duoc.bancoxyz.common.excepcion;

/**
 * Un microservicio del que dependemos no respondio. Es la excepcion que
 * Resilience4j cuenta como fallo para abrir el circuito: un 404 del otro
 * servicio no es esto, porque significa que si respondio.
 */
public class ServicioNoDisponibleException extends RuntimeException {

    public ServicioNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public ServicioNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
