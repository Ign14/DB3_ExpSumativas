package cl.duoc.bancoxyz.bff.common.client;

import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Cliente hacia core-movimientos-service, compartido por los tres BFF para no
 * repetir la integración en cada canal.
 */
public class MovimientosApiClient {

    private static final String SERVICIO = "core-movimientos-service";

    private final RestClient restClient;

    public MovimientosApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Historial completo de la cuenta. */
    public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
        try {
            return restClient.get()
                    .uri("/api/movimientos/{cuentaId}", cuentaId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<MovimientoDTO>>() {
                    });
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }

    /**
     * Los movimientos más recientes, recortados por el servicio core. Los
     * canales que solo necesitan unos pocos evitan así que viaje el historial
     * completo entre el core y el BFF.
     */
    public List<MovimientoDTO> obtenerUltimosMovimientos(Long cuentaId, int limite) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/movimientos/{cuentaId}")
                            .queryParam("limite", limite)
                            .build(cuentaId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<MovimientoDTO>>() {
                    });
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }

    public ResumenMovimientosDTO obtenerResumen(Long cuentaId) {
        try {
            return restClient.get()
                    .uri("/api/movimientos/{cuentaId}/resumen", cuentaId)
                    .retrieve()
                    .body(ResumenMovimientosDTO.class);
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }

    /** Registra un movimiento nuevo, como el que genera un retiro por cajero. */
    public MovimientoDTO registrarMovimiento(MovimientoDTO movimiento) {
        try {
            return restClient.post()
                    .uri("/api/movimientos")
                    .body(movimiento)
                    .retrieve()
                    .body(MovimientoDTO.class);
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }

    public TransaccionDiariaResumenDTO obtenerResumenTransaccionesDiarias() {
        try {
            return restClient.get()
                    .uri("/api/banco/transacciones-diarias/resumen")
                    .retrieve()
                    .body(TransaccionDiariaResumenDTO.class);
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }
}
