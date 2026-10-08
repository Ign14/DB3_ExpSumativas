package cl.duoc.bancoxyz.pagos.cliente;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.excepcion.LlamadaRechazadaException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Integracion HTTP con cuentas-service.
 *
 * La clasificacion de las respuestas es la parte que importa, porque es la que
 * decide que cuenta como fallo para el circuit breaker:
 *
 * - **404**: el servicio esta sano y la cuenta no existe. No es un fallo, y se
 *   devuelve como un resultado normal.
 * - **Otro 4xx** (401, 403, 400): el servicio respondio y rechazo la llamada.
 *   Tampoco es un fallo del servicio: es nuestro problema de configuracion, y
 *   reintentar o abrir el circuito no lo arregla ni lo hace visible.
 * - **5xx y errores de red**: el servicio no esta disponible. Esto si es un
 *   fallo, y es lo unico que el circuit breaker registra.
 *
 * Implementa los dos gateways porque la integracion es la misma: el mismo
 * cliente HTTP, el mismo token, la misma clasificacion de respuestas. Lo que se
 * separa en dos interfaces es el contrato que ven los consumidores, no la
 * forma de hablar con el servicio.
 */
@Component
public class CuentasGatewayHttp implements CuentasGateway, LiquidacionGateway {

    private final RestClient clienteCuentas;

    public CuentasGatewayHttp(RestClient clienteCuentas) {
        this.clienteCuentas = clienteCuentas;
    }

    @Override
    public ResultadoCuenta obtenerCuenta(Long cuentaId) {
        try {
            CuentaDTO cuenta = clienteCuentas.get()
                    .uri("/cuentas/{cuentaId}", cuentaId)
                    .exchange((peticion, respuesta) -> {
                        HttpStatusCode estado = respuesta.getStatusCode();
                        if (estado.value() == 404) {
                            return null;
                        }
                        if (estado.is4xxClientError()) {
                            throw new LlamadaRechazadaException(
                                    "cuentas-service rechazo la llamada con " + estado.value()
                                            + ": revise las credenciales del cliente OAuth2 y el issuer",
                                    estado.value());
                        }
                        if (!estado.is2xxSuccessful()) {
                            throw new ServicioNoDisponibleException(
                                    "cuentas-service respondio " + estado.value());
                        }
                        return respuesta.bodyTo(CuentaDTO.class);
                    });
            return cuenta == null ? ResultadoCuenta.noEncontrada() : ResultadoCuenta.encontrada(cuenta);
        } catch (ServicioNoDisponibleException | LlamadaRechazadaException ex) {
            throw ex;
        } catch (Exception ex) {
            // Conexion rechazada, timeout de lectura, DNS, token no obtenible:
            // todo eso es "el servicio no esta disponible para mi".
            throw new ServicioNoDisponibleException(
                    "No se pudo consultar la cuenta " + cuentaId + " en cuentas-service", ex);
        }
    }

    @Override
    public ResultadoLiquidacion cargar(Long cuentaId, BigDecimal monto) {
        RespuestaLiquidacion respuesta = liquidar("cargo", cuentaId, monto);
        return respuesta.aplicado()
                ? ResultadoLiquidacion.aplicada(respuesta.saldoResultante())
                : ResultadoLiquidacion.rechazada(respuesta.motivoRechazo());
    }

    @Override
    public BigDecimal abonar(Long cuentaId, BigDecimal monto) {
        RespuestaLiquidacion respuesta = liquidar("abono", cuentaId, monto);
        if (!respuesta.aplicado()) {
            // cuentas-service no rechaza abonos por saldo. Si alguna vez lo
            // hiciera, seria un cambio de contrato y no algo que este servicio
            // deba interpretar por su cuenta.
            throw new ServicioNoDisponibleException(
                    "cuentas-service rechazo un abono en la cuenta " + cuentaId
                            + ", lo que no esta previsto en el contrato: " + respuesta.motivoRechazo());
        }
        return respuesta.saldoResultante();
    }

    /**
     * Las dos primitivas comparten la clasificacion de respuestas con la
     * consulta, con una diferencia importante: aqui un 404 no es un resultado
     * normal sino un error. Consultar una cuenta que no existe es una pregunta
     * legitima con respuesta "no existe"; liquidar sobre una cuenta que no
     * existe significa que el sistema intento mover dinero a ninguna parte, y eso
     * tiene que llegar hasta quien orqueste la operacion.
     */
    private RespuestaLiquidacion liquidar(String operacion, Long cuentaId, BigDecimal monto) {
        try {
            RespuestaLiquidacion respuesta = clienteCuentas.post()
                    .uri("/cuentas/{cuentaId}/{operacion}", cuentaId, operacion)
                    .body(Map.of("monto", monto))
                    .exchange((peticion, http) -> {
                        HttpStatusCode estado = http.getStatusCode();
                        if (estado.value() == 404) {
                            throw new RecursoNoEncontradoException(
                                    "La cuenta " + cuentaId + " no existe en cuentas-service.");
                        }
                        if (estado.is4xxClientError()) {
                            throw new LlamadaRechazadaException(
                                    "cuentas-service rechazo el " + operacion + " con " + estado.value()
                                            + ": revise el scope cuentas.liquidar del cliente OAuth2",
                                    estado.value());
                        }
                        if (!estado.is2xxSuccessful()) {
                            throw new ServicioNoDisponibleException(
                                    "cuentas-service respondio " + estado.value() + " al " + operacion);
                        }
                        return http.bodyTo(RespuestaLiquidacion.class);
                    });
            if (respuesta == null) {
                throw new ServicioNoDisponibleException(
                        "cuentas-service respondio sin cuerpo al " + operacion
                                + " de la cuenta " + cuentaId);
            }
            return respuesta;
        } catch (ServicioNoDisponibleException | LlamadaRechazadaException
                 | RecursoNoEncontradoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioNoDisponibleException(
                    "No se pudo ejecutar el " + operacion + " de " + monto
                            + " en la cuenta " + cuentaId, ex);
        }
    }

    /** Cuerpo con el que responde cuentas-service a /cargo y /abono. */
    record RespuestaLiquidacion(Long cuentaId, boolean aplicado,
                                BigDecimal saldoResultante, String motivoRechazo) {
    }
}
