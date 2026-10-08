package cl.duoc.bancoxyz.pagos.cliente;

/**
 * La llamada HTTP cruda a cuentas-service, sin ninguna politica de resiliencia.
 *
 * Existe como interfaz para que la tolerancia a fallos y la integracion HTTP
 * queden en clases distintas: las pruebas pueden sustituir esta mitad por un
 * doble que falla o que tarda, y verificar la otra mitad sin levantar un
 * servidor.
 *
 * Tiene un solo metodo, y lo mantiene asi deliberadamente. Las operaciones que
 * mueven saldo viven en {@link LiquidacionGateway}, que es otra interfaz y no un
 * par de metodos mas aqui. La razon no es estetica: leer una cuenta y mover su
 * dinero exigen politicas de resiliencia distintas —la lectura tiene respuesta
 * degradada, la liquidacion no puede tenerla—, y separar los contratos impide
 * que una clase quede obligada a implementar las dos cosas cuando solo le
 * importa una. De paso, al tener un unico metodo abstracto, un doble de prueba
 * se escribe como una expresion lambda de una linea.
 */
public interface CuentasGateway {

    /**
     * @throws cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException
     *         si cuentas-service no responde o responde 5xx. Un 404 no es esto:
     *         se devuelve como {@link ResultadoCuenta.Origen#NO_ENCONTRADA}.
     */
    ResultadoCuenta obtenerCuenta(Long cuentaId);

}
