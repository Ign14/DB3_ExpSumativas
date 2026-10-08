package cl.duoc.bancoxyz.bff.common.client;

import cl.duoc.bancoxyz.common.dto.ClienteDTO;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Cliente hacia el dominio de clientes a traves del api-gateway.
 *
 * Lo usan el canal web, que muestra y edita el perfil, y nadie mas. El canal
 * movil no lo consume a proposito: su pantalla de resumen necesita saldo y
 * ultimos movimientos, no el perfil comercial del titular, y pedirlo seria
 * cargar la respuesta con datos personales que la app no va a mostrar. El
 * cajero tampoco: no tiene scope para este dominio.
 */
public class ClientesApiClient {

    private static final String SERVICIO = "clientes-service";

    private final RestClient restClient;

    public ClientesApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ClienteDTO obtenerPerfil(Long clienteId) {
        try {
            return restClient.get()
                    .uri("/api/clientes/{clienteId}", clienteId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new CuentaNoEncontradaException(clienteId);
                        }
                        throw new ServicioCoreNoDisponibleException(
                                SERVICIO + " respondio " + res.getStatusCode());
                    })
                    .body(ClienteDTO.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }

    public ClienteDTO actualizarNombre(Long clienteId, String nombre) {
        try {
            return restClient.put()
                    .uri("/api/clientes/{clienteId}/nombre", clienteId)
                    .body(Map.of("nombre", nombre))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new CuentaNoEncontradaException(clienteId);
                        }
                        throw new ServicioCoreNoDisponibleException(
                                SERVICIO + " respondio " + res.getStatusCode() + " al actualizar el nombre");
                    })
                    .body(ClienteDTO.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }
}
