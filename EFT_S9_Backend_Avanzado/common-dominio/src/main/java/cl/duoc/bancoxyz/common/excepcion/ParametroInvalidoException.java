package cl.duoc.bancoxyz.common.excepcion;

/** La peticion llego mal formada: se traduce a 400. */
public class ParametroInvalidoException extends RuntimeException {

    public ParametroInvalidoException(String mensaje) {
        super(mensaje);
    }
}
