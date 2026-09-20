package cl.duoc.bancoxyz.bff.movil.service;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.exception.ParametroInvalidoException;
import cl.duoc.bancoxyz.bff.movil.dto.CuentaMovilResponse;
import cl.duoc.bancoxyz.bff.movil.dto.MovimientoMovilDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Agregación y recorte de los servicios core para el canal móvil. */
@Service
public class CuentaMovilService {

    /** Tope del canal: la app nunca muestra más movimientos que esto en el resumen. */
    public static final int LIMITE_MAXIMO = 20;

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;

    public CuentaMovilService(CuentasApiClient cuentasApiClient, MovimientosApiClient movimientosApiClient) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
    }

    /**
     * Misma agregación que el canal web, pero devolviendo bastante menos: sin
     * nombre del titular (la app ya lo tiene de la sesión), sin descripciones,
     * y solo los últimos movimientos.
     *
     * El recorte se le pide al servicio core para que el historial completo
     * tampoco viaje entre el core y este BFF.
     */
    public CuentaMovilResponse obtenerResumenLiviano(Long cuentaId, int limite) {
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new ParametroInvalidoException(
                    "El parámetro 'limite' debe estar entre 1 y " + LIMITE_MAXIMO);
        }

        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        List<MovimientoDTO> ultimos = movimientosApiClient.obtenerUltimosMovimientos(cuentaId, limite);

        List<MovimientoMovilDTO> compactos = ultimos.stream()
                .map(m -> new MovimientoMovilDTO(m.fecha(), m.tipoMovimiento(), m.monto()))
                .toList();

        return new CuentaMovilResponse(cuenta.saldo(), cuenta.tipoCuenta(), compactos);
    }
}
