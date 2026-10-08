package cl.duoc.bancoxyz.common.excepcion;

/**
 * El otro microservicio respondio, pero rechazo la llamada: 401, 403, 400.
 *
 * Es importante que no sea un {@link ServicioNoDisponibleException}. Un 401
 * significa que nuestro token no sirve —el secreto esta mal, o el auth-server se
 * reinicio y cambio su clave de firma—, y eso no se arregla reintentando ni
 * esperando: es un problema de configuracion que va a seguir ahi. Si contara
 * como fallo del servicio, el circuito se abriria y el log diria "respuesta
 * degradada" cuando lo que hay que hacer es revisar las credenciales.
 */
public class LlamadaRechazadaException extends RuntimeException {

    private final int codigoHttp;

    public LlamadaRechazadaException(String mensaje, int codigoHttp) {
        super(mensaje);
        this.codigoHttp = codigoHttp;
    }

    public int codigoHttp() {
        return codigoHttp;
    }
}
