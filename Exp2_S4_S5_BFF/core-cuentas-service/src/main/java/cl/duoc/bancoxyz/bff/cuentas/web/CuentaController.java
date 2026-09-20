package cl.duoc.bancoxyz.bff.cuentas.web;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.DebitoRequest;
import cl.duoc.bancoxyz.bff.common.dto.DebitoResponse;
import cl.duoc.bancoxyz.bff.common.dto.ErrorResponse;
import cl.duoc.bancoxyz.bff.cuentas.domain.CuentaRepositoryEnMemoria;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * API de dominio de cuentas. No distingue canales: esa adaptación es
 * responsabilidad de los BFF que la consumen.
 */
@RestController
@RequestMapping("/api/cuentas")
public class CuentaController {

    private final CuentaRepositoryEnMemoria repositorio;

    public CuentaController(CuentaRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping
    public List<CuentaDTO> listar() {
        return repositorio.listar();
    }

    @GetMapping("/{cuentaId}")
    public ResponseEntity<?> obtener(@PathVariable Long cuentaId) {
        return repositorio.buscar(cuentaId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ErrorResponse("No existe la cuenta " + cuentaId)));
    }

    @PatchMapping("/{cuentaId}/debitar")
    public ResponseEntity<?> debitar(@PathVariable Long cuentaId, @RequestBody DebitoRequest request) {
        BigDecimal monto = request.monto();
        if (monto == null || monto.signum() <= 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("El monto a debitar debe ser mayor que cero"));
        }
        return repositorio.debitar(cuentaId, monto)
                .<ResponseEntity<?>>map(resultado -> ResponseEntity.ok(new DebitoResponse(
                        cuentaId, monto, resultado.saldoResultante(), resultado.aprobado(), resultado.motivoRechazo())))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ErrorResponse("No existe la cuenta " + cuentaId)));
    }
}
