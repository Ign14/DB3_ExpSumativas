package cl.duoc.bancoxyz.bff.movimientos.web;

import cl.duoc.bancoxyz.bff.common.dto.ErrorResponse;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.movimientos.domain.MovimientoRepositoryEnMemoria;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * API de dominio de movimientos por cuenta. La ausencia de movimientos no es
 * un error: una cuenta nueva o sin actividad simplemente devuelve una lista
 * vacia y un resumen en cero (la existencia de la cuenta en si la valida
 * core-cuentas-service).
 */
@RestController
@RequestMapping("/api/movimientos")
public class MovimientoController {

    private static final int LIMITE_MAXIMO = 100;

    private final MovimientoRepositoryEnMemoria repositorio;

    public MovimientoController(MovimientoRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * Sin {@code limite} devuelve el historial completo (canal web); con
     * {@code limite} devuelve solo los N mas recientes, recortados aca y no
     * en el BFF (canales movil y cajero).
     */
    @GetMapping("/{cuentaId}")
    public ResponseEntity<?> obtener(@PathVariable Long cuentaId,
                                     @RequestParam(required = false) Integer limite) {
        if (limite == null) {
            return ResponseEntity.ok(repositorio.obtenerMovimientos(cuentaId));
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "El parametro 'limite' debe estar entre 1 y " + LIMITE_MAXIMO));
        }
        return ResponseEntity.ok(repositorio.obtenerUltimos(cuentaId, limite));
    }

    @GetMapping("/{cuentaId}/resumen")
    public ResumenMovimientosDTO resumen(@PathVariable Long cuentaId) {
        return repositorio.resumir(cuentaId);
    }

    /**
     * Registra un movimiento nuevo en el historial. Lo usa el BFF de cajero
     * para dejar constancia del retiro recien aplicado sobre el saldo, de
     * modo que la operacion hecha en el cajero sea visible despues desde
     * los canales web y movil.
     */
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
        MovimientoDTO registrado = repositorio.registrar(movimiento);
        return ResponseEntity.status(201).body(registrado);
    }
}
