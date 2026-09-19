package cl.duoc.bancoxyz.bff.common.client;

import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Cliente tipado hacia core-movimientos-service, compartido por los tres BFF.
 */
public class MovimientosApiClient {

    private final RestClient restClient;

    public MovimientosApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
        try {
            return restClient.get()
                    .uri("/api/movimientos/{cuentaId}", cuentaId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<MovimientoDTO>>() {
                    });
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException("core-movimientos-service", ex);
        }
    }

    public ResumenMovimientosDTO obtenerResumen(Long cuentaId) {
        try {
            return restClient.get()
                    .uri("/api/movimientos/{cuentaId}/resumen", cuentaId)
                    .retrieve()
                    .body(ResumenMovimientosDTO.class);
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException("core-movimientos-service", ex);
        }
    }

    public TransaccionDiariaResumenDTO obtenerResumenTransaccionesDiarias() {
        try {
            return restClient.get()
                    .uri("/api/banco/transacciones-diarias/resumen")
                    .retrieve()
                    .body(TransaccionDiariaResumenDTO.class);
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException("core-movimientos-service", ex);
        }
    }
}
