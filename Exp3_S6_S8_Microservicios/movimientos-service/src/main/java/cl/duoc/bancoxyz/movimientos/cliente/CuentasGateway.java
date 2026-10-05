package cl.duoc.bancoxyz.movimientos.cliente;

/**
 * La llamada HTTP cruda a cuentas-service, sin ninguna politica de resiliencia.
 *
 * Existe como interfaz para que la tolerancia a fallos y la integracion HTTP
 * queden en clases distintas: las pruebas pueden sustituir esta mitad por un
 * doble que falla o que tarda, y verificar la otra mitad sin levantar un
 * servidor.
 */
public interface CuentasGateway {

    /**
     * @throws cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException
     *         si cuentas-service no responde o responde 5xx. Un 404 no es esto:
     *         se devuelve como {@link ResultadoCuenta.Origen#NO_ENCONTRADA}.
     */
    ResultadoCuenta obtenerCuenta(Long cuentaId);
}
