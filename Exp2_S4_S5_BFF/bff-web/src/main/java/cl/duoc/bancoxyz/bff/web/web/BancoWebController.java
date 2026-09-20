package cl.duoc.bancoxyz.bff.web.web;

import cl.duoc.bancoxyz.bff.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.bff.web.service.CuentaWebService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel administrativo, exclusivo del canal web: ni la app móvil ni el cajero
 * tienen un caso de uso para el resumen del banco completo.
 */
@RestController
@RequestMapping("/web/banco")
public class BancoWebController {

    private final CuentaWebService servicio;

    public BancoWebController(CuentaWebService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/resumen-diario")
    public TransaccionDiariaResumenDTO resumenDiario() {
        return servicio.obtenerResumenDiario();
    }
}
