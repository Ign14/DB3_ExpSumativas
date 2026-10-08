package cl.duoc.bancoxyz.common.excepcion;

/** El recurso pedido no existe: se traduce a 404. */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
