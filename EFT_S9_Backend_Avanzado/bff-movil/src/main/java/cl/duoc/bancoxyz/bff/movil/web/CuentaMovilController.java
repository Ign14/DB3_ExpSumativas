package cl.duoc.bancoxyz.bff.movil.web;

import cl.duoc.bancoxyz.bff.movil.dto.CuentaMovilResponse;
import cl.duoc.bancoxyz.bff.movil.service.CuentaMovilService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/movil/cuentas")
public class CuentaMovilController {

    private static final int MOVIMIENTOS_POR_DEFECTO = 5;

    private final CuentaMovilService servicio;

    public CuentaMovilController(CuentaMovilService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/{cuentaId}")
    public CuentaMovilResponse obtenerResumenLiviano(
            @PathVariable Long cuentaId,
            @RequestParam(defaultValue = "" + MOVIMIENTOS_POR_DEFECTO) int limite) {
        return servicio.obtenerResumenLiviano(cuentaId, limite);
    }
}
