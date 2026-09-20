package cl.duoc.bancoxyz.bff.movil.web;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.exception.ParametroInvalidoException;
import cl.duoc.bancoxyz.bff.movil.dto.CuentaMovilResponse;
import cl.duoc.bancoxyz.bff.movil.dto.MovimientoMovilDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/movil/cuentas")
public class CuentaMovilController {

    private static final int MOVIMIENTOS_POR_DEFECTO = 5;
    /** Tope duro del canal: la app movil nunca necesita mas que esto. */
    private static final int LIMITE_MAXIMO = 20;

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;

    public CuentaMovilController(CuentasApiClient cuentasApiClient, MovimientosApiClient movimientosApiClient) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
    }

    /**
     * Misma agregacion de dos servicios core que hace el BFF Web, pero
     * transformada a un payload deliberadamente mas chico: sin nombre del
     * titular, sin descripciones, y solo los ultimos N movimientos.
     *
     * <p>El recorte se le pide al servicio core (no se trae el historial
     * completo para descartarlo aca), de modo que el ahorro exista tambien
     * en el trafico interno y no solo en el payload que ve la app.</p>
     */
    @GetMapping("/{cuentaId}")
    public CuentaMovilResponse obtenerResumenLiviano(
            @PathVariable Long cuentaId,
            @RequestParam(defaultValue = "" + MOVIMIENTOS_POR_DEFECTO) int limite) {

        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new ParametroInvalidoException(
                    "El parametro 'limite' debe estar entre 1 y " + LIMITE_MAXIMO);
        }

        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        List<MovimientoDTO> ultimos = movimientosApiClient.obtenerUltimosMovimientos(cuentaId, limite);

        List<MovimientoMovilDTO> compactos = ultimos.stream()
                .map(m -> new MovimientoMovilDTO(m.fecha(), m.tipoMovimiento(), m.monto()))
                .toList();

        return new CuentaMovilResponse(cuenta.saldo(), cuenta.tipoCuenta(), compactos);
    }
}
