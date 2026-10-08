package cl.duoc.bancoxyz.bff.common.client;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.RetiroRequest;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cliente hacia el dominio de cuentas a traves del api-gateway, compartido por
 * los tres BFF para no repetir la integracion en cada canal.
 */
public class CuentasApiClient {

    private static final String SERVICIO = "cuentas-service";

    private final RestClient restClient;

    public CuentasApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<CuentaDTO> listarCuentas() {
        try {
            return restClient.get()
                    .uri("/api/cuentas")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CuentaDTO>>() {
                    });
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }

    public CuentaDTO obtenerCuenta(Long cuentaId) {
        try {
            return restClient.get()
                    .uri("/api/cuentas/{cuentaId}", cuentaId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new CuentaNoEncontradaException(cuentaId);
                        }
                        throw new ServicioCoreNoDisponibleException(
                                SERVICIO + " respondió " + res.getStatusCode());
                    })
                    .body(CuentaDTO.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }

    /**
     * Pide un retiro.
     *
     * Dos cosas cambiaron respecto de la version de la semana 5, y las dos
     * simplifican al canal:
     *
     * - El endpoint es {@code POST /retiro} y no un {@code PATCH /debitar}. El
     *   canal pide una operacion de negocio, no una modificacion de un campo; el
     *   dominio decide si la aprueba y con que limite.
     * - El rechazo por fondos sigue llegando como 200 con {@code aprobado:
     *   false}, igual que antes, de modo que el canal no tiene que distinguir
     *   entre una respuesta de negocio y un error de protocolo.
     *
     * Y una que desaparecio: ya no hace falta que el canal registre el
     * movimiento despues del retiro. Ahora cuentas-service publica el evento y
     * pagos-service lo consume de la cola, asi que el historial se actualiza sin
     * que ningun canal lo orqueste.
     */
    public RetiroResponse retirar(Long cuentaId, BigDecimal monto, String canal) {
        try {
            return restClient.post()
                    .uri("/api/cuentas/{cuentaId}/retiro", cuentaId)
                    .body(new RetiroRequest(monto, canal))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new CuentaNoEncontradaException(cuentaId);
                        }
                        throw new ServicioCoreNoDisponibleException(
                                SERVICIO + " respondio " + res.getStatusCode() + " al retirar");
                    })
                    .body(RetiroResponse.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }
}
