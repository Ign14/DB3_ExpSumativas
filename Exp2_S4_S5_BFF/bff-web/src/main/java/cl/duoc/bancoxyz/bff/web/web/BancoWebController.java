package cl.duoc.bancoxyz.bff.web.web;

import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.TransaccionDiariaResumenDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel administrativo, exclusivo del canal Web: los otros dos canales
 * (movil y cajero) no tienen ningun cliente interesado en ver el resumen
 * de transacciones del banco completo.
 */
@RestController
@RequestMapping("/web/banco")
public class BancoWebController {

    private final MovimientosApiClient movimientosApiClient;

    public BancoWebController(MovimientosApiClient movimientosApiClient) {
        this.movimientosApiClient = movimientosApiClient;
    }

    @GetMapping("/resumen-diario")
    public TransaccionDiariaResumenDTO resumenDiario() {
        return movimientosApiClient.obtenerResumenTransaccionesDiarias();
    }
}
