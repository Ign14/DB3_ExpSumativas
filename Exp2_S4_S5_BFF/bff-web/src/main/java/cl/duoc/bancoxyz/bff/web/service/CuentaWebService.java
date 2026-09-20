package cl.duoc.bancoxyz.bff.web.service;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.bff.web.dto.CuentaWebResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/** Agregación de los servicios core para el canal web. */
@Service
public class CuentaWebService {

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;

    public CuentaWebService(CuentasApiClient cuentasApiClient, MovimientosApiClient movimientosApiClient) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
    }

    /**
     * Una sola petición del cliente web se traduce en tres llamadas a dos
     * servicios core. Esa agregación es el trabajo que el patrón BFF le quita
     * al frontend.
     */
    public CuentaWebResponse obtenerCuentaCompleta(Long cuentaId) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        List<MovimientoDTO> historial = movimientosApiClient.obtenerMovimientos(cuentaId);
        ResumenMovimientosDTO resumen = movimientosApiClient.obtenerResumen(cuentaId);
        return new CuentaWebResponse(cuenta, historial, resumen);
    }

    public List<CuentaDTO> listarCuentas() {
        return cuentasApiClient.listarCuentas();
    }

    public TransaccionDiariaResumenDTO obtenerResumenDiario() {
        return movimientosApiClient.obtenerResumenTransaccionesDiarias();
    }
}
