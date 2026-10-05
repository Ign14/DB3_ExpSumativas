package cl.duoc.bancoxyz.movimientos.cliente;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.excepcion.LlamadaRechazadaException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
 */
@Component
public class CuentasGatewayHttp implements CuentasGateway {

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
}
