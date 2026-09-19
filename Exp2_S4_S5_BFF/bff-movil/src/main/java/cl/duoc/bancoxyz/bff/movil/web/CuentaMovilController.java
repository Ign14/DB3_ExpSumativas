package cl.duoc.bancoxyz.bff.movil.web;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.movil.dto.CuentaMovilResponse;
import cl.duoc.bancoxyz.bff.movil.dto.MovimientoMovilDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/movil/cuentas")
public class CuentaMovilController {

    private static final int MOVIMIENTOS_POR_DEFECTO = 5;

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;

    public CuentaMovilController(CuentasApiClient cuentasApiClient, MovimientosApiClient movimientosApiClient) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
    }

    /**
     * Misma agregacion de dos servicios core que hace el BFF Web, pero
     * transformada a un payload deliberadamente mas chico: sin nombre del
     * titular, sin descripciones, y solo los ultimos N movimientos en vez
     * del historial completo.
     */
    @GetMapping("/{cuentaId}")
    public CuentaMovilResponse obtenerResumenLiviano(
            @PathVariable Long cuentaId,
            @RequestParam(defaultValue = "" + MOVIMIENTOS_POR_DEFECTO) int limite) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        List<MovimientoDTO> historial = movimientosApiClient.obtenerMovimientos(cuentaId);

        List<MovimientoMovilDTO> ultimos = historial.stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha).reversed())
                .limit(limite)
                .map(m -> new MovimientoMovilDTO(m.fecha(), m.tipoMovimiento(), m.monto()))
                .toList();

        return new CuentaMovilResponse(cuenta.saldo(), cuenta.tipoCuenta(), ultimos);
    }
}
