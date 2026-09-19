package cl.duoc.bancoxyz.bff.common.client;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.DebitoRequest;
import cl.duoc.bancoxyz.bff.common.dto.DebitoResponse;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cliente tipado hacia core-cuentas-service. Los tres BFF lo comparten (via
 * common-model) en vez de reimplementar cada uno su propia llamada HTTP:
 * asi, si cambia la forma de invocar al core, se ajusta en un solo lugar.
 */
public class CuentasApiClient {

    private final RestClient restClient;

    public CuentasApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<CuentaDTO> listarCuentas() {
        try {
            return restClient.get()
                    .uri("/api/cuentas")
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<CuentaDTO>>() {
                    });
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException("core-cuentas-service", ex);
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
                                "core-cuentas-service respondio " + res.getStatusCode());
                    })
                    .body(CuentaDTO.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException("core-cuentas-service", ex);
        }
    }

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
                                "core-cuentas-service respondio " + res.getStatusCode() + " al debitar");
                    })
                    .body(DebitoResponse.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException("core-cuentas-service", ex);
        }
    }
}
