package cl.duoc.bancoxyz.bff.web.web;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.web.dto.CuentaWebResponse;
import cl.duoc.bancoxyz.bff.web.service.CuentaWebService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/web/cuentas")
public class CuentaWebController {

    private final CuentaWebService servicio;

    public CuentaWebController(CuentaWebService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/{cuentaId}")
    public CuentaWebResponse obtenerCuentaCompleta(@PathVariable Long cuentaId) {
        return servicio.obtenerCuentaCompleta(cuentaId);
    }

    @GetMapping
    public List<CuentaDTO> listarCuentas() {
        return servicio.listarCuentas();
    }
}
