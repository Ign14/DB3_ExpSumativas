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

    /** Historial completo de la cuenta. Lo usa el canal web. */
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

    /**
     * Solo los ultimos {@code limite} movimientos, recortados <b>en el
     * servicio core</b>. Lo usan los canales que no necesitan el historial
     * completo (movil y cajero): asi el ahorro no es solo en el payload que
     * ve el cliente, sino tambien en lo que viaja entre el BFF y el core.
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

    /**
     * Registra un movimiento nuevo en el historial de la cuenta. Lo usa el
     * canal cajero para dejar constancia del retiro que acaba de aplicar
     * sobre el saldo.
     */
    public MovimientoDTO registrarMovimiento(MovimientoDTO movimiento) {
        try {
            return restClient.post()
                    .uri("/api/movimientos")
                    .body(movimiento)
                    .retrieve()
                    .body(MovimientoDTO.class);
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
