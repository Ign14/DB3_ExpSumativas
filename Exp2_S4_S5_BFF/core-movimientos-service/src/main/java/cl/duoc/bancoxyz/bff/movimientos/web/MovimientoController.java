package cl.duoc.bancoxyz.bff.movimientos.web;

import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.movimientos.domain.MovimientoRepositoryEnMemoria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API de dominio de movimientos por cuenta. La ausencia de movimientos no es
 * un error: una cuenta nueva o sin actividad simplemente devuelve una lista
 * vacia y un resumen en cero (la existencia de la cuenta en si la valida
 * core-cuentas-service).
 */
@RestController
@RequestMapping("/api/movimientos")
public class MovimientoController {

    private final MovimientoRepositoryEnMemoria repositorio;

    public MovimientoController(MovimientoRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping("/{cuentaId}")
    public List<MovimientoDTO> obtener(@PathVariable Long cuentaId) {
        return repositorio.obtenerMovimientos(cuentaId);
    }

    @GetMapping("/{cuentaId}/resumen")
    public ResumenMovimientosDTO resumen(@PathVariable Long cuentaId) {
        return repositorio.resumir(cuentaId);
    }
}
