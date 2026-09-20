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

    /**
     * Lo usa el BFF de cajero para dejar constancia de un retiro. El
     * movimiento pasa por las mismas reglas de validación que se aplican al
     * cargar el CSV, y se devuelve ya normalizado.
     */
    @PostMapping
    public ResponseEntity<?> registrar(@RequestBody MovimientoDTO movimiento) {
        return repositorio.registrar(movimiento)
                .<ResponseEntity<?>>map(registrado -> ResponseEntity.status(201).body(registrado))
                .orElseGet(() -> ResponseEntity.badRequest().body(new ErrorResponse(
                        "El movimiento no cumple las reglas de validación: requiere cuentaId, "
                                + "una fecha interpretable, un tipo en {compra, deposito, pago, retiro} "
                                + "y un monto mayor que cero")));
    }
}
