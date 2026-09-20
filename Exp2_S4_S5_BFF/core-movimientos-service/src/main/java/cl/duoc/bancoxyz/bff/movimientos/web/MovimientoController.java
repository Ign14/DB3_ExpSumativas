package cl.duoc.bancoxyz.bff.movimientos.web;

import cl.duoc.bancoxyz.bff.common.dto.ErrorResponse;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.movimientos.domain.MovimientoRepositoryEnMemoria;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * API de dominio de movimientos. Que una cuenta no tenga movimientos no es un
 * error: devuelve lista vacía y resumen en cero. La existencia de la cuenta la
 * valida core-cuentas-service.
 */
@RestController
@RequestMapping("/api/movimientos")
public class MovimientoController {

    private static final int LIMITE_MAXIMO = 100;

    private final MovimientoRepositoryEnMemoria repositorio;

    public MovimientoController(MovimientoRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    /** Sin {@code limite} devuelve el historial completo; con él, solo los N más recientes. */
    @GetMapping("/{cuentaId}")
    public ResponseEntity<?> obtener(@PathVariable Long cuentaId,
                                     @RequestParam(required = false) Integer limite) {
        if (limite == null) {
            return ResponseEntity.ok(repositorio.obtenerMovimientos(cuentaId));
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "El parámetro 'limite' debe estar entre 1 y " + LIMITE_MAXIMO));
        }
        return ResponseEntity.ok(repositorio.obtenerUltimos(cuentaId, limite));
    }

    @GetMapping("/{cuentaId}/resumen")
    public ResumenMovimientosDTO resumen(@PathVariable Long cuentaId) {
        return repositorio.resumir(cuentaId);
    }

    /** Lo usa el BFF de cajero para dejar constancia de un retiro. */
    @PostMapping
    public ResponseEntity<?> registrar(@RequestBody MovimientoDTO movimiento) {
        if (movimiento.cuentaId() == null
                || movimiento.fecha() == null || movimiento.fecha().isBlank()
                || movimiento.tipoMovimiento() == null || movimiento.tipoMovimiento().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("cuentaId, fecha y tipoMovimiento son obligatorios"));
        }
        if (movimiento.monto() == null || movimiento.monto().signum() <= 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("El monto del movimiento debe ser mayor que cero"));
        }
        return ResponseEntity.status(201).body(repositorio.registrar(movimiento));
    }
}
