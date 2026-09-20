package cl.duoc.bancoxyz.bff.common.client;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.DebitoRequest;
import cl.duoc.bancoxyz.bff.common.dto.DebitoResponse;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cliente hacia core-cuentas-service, compartido por los tres BFF para no
 * repetir la integración en cada canal.
 */
public class CuentasApiClient {

    private static final String SERVICIO = "core-cuentas-service";

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
     * Los fondos insuficientes no son un error de protocolo: el core responde
     * 200 con {@code aprobado = false}, y esta llamada devuelve ese resultado
     * en vez de lanzar una excepción.
     */
    public DebitoResponse debitar(Long cuentaId, BigDecimal monto) {
        try {
            return restClient.patch()
                    .uri("/api/cuentas/{cuentaId}/debitar", cuentaId)
                    .body(new DebitoRequest(monto))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new CuentaNoEncontradaException(cuentaId);
                        }
                        throw new ServicioCoreNoDisponibleException(
                                SERVICIO + " respondió " + res.getStatusCode() + " al debitar");
                    })
                    .body(DebitoResponse.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }
}
