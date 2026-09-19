package cl.duoc.bancoxyz.bff.web.web;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.web.dto.CuentaWebResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/web/cuentas")
public class CuentaWebController {

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;

    public CuentaWebController(CuentasApiClient cuentasApiClient, MovimientosApiClient movimientosApiClient) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
    }

    /**
     * Un unico llamado del cliente web dispara, hacia adentro, tres
     * llamadas a dos servicios core distintos: esa agregacion es
     * exactamente el trabajo que el patron BFF le quita al frontend.
     */
    @GetMapping("/{cuentaId}")
    public CuentaWebResponse obtenerCuentaCompleta(@PathVariable Long cuentaId) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        List<MovimientoDTO> historial = movimientosApiClient.obtenerMovimientos(cuentaId);
        ResumenMovimientosDTO resumen = movimientosApiClient.obtenerResumen(cuentaId);
        return new CuentaWebResponse(cuenta, historial, resumen);
    }

    @GetMapping
    public List<CuentaDTO> listarCuentas() {
        return cuentasApiClient.listarCuentas();
    }
}
