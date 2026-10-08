package cl.duoc.bancoxyz.bff.common.client;

import cl.duoc.bancoxyz.common.dto.OperacionResponse;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Cliente hacia las operaciones de pago a traves del api-gateway.
 *
 * Solo lo consume el canal web, que es el unico con scope de escritura en el
 * dominio de movimientos. Un deposito o una transferencia rechazados por reglas
 * de negocio llegan como 200 con {@code aprobada: false}, igual que el retiro.
 */
public class PagosApiClient {

    private static final String SERVICIO = "pagos-service";

    private final RestClient restClient;

    public PagosApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public OperacionResponse depositar(Long cuentaId, BigDecimal monto,
                                           String canal, String descripcion) {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("monto", monto);
        cuerpo.put("canal", canal);
        cuerpo.put("descripcion", descripcion);
        return enviar("/api/pagos/deposito/{cuentaId}", cuentaId, cuerpo, "depositar");
    }

    public OperacionResponse transferir(Long cuentaOrigen, Long cuentaDestino, BigDecimal monto,
                                            String canal, String descripcion) {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("cuentaDestino", cuentaDestino);
        cuerpo.put("monto", monto);
        cuerpo.put("canal", canal);
        cuerpo.put("descripcion", descripcion);
        return enviar("/api/pagos/transferencia/{cuentaOrigen}", cuentaOrigen, cuerpo, "transferir");
    }

    private OperacionResponse enviar(String ruta, Long cuenta,
                                         Map<String, Object> cuerpo, String operacion) {
        try {
            return restClient.post()
                    .uri(ruta, cuenta)
                    .body(cuerpo)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new CuentaNoEncontradaException(cuenta);
                        }
                        throw new ServicioCoreNoDisponibleException(
                                SERVICIO + " respondio " + res.getStatusCode() + " al " + operacion);
                    })
                    .body(OperacionResponse.class);
        } catch (CuentaNoEncontradaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServicioCoreNoDisponibleException(SERVICIO, ex);
        }
    }
}
