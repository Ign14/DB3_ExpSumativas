package cl.duoc.bancoxyz.bff.cajero.web;

import cl.duoc.bancoxyz.bff.cajero.dto.RetiroRequest;
import cl.duoc.bancoxyz.bff.cajero.dto.RetiroResponse;
import cl.duoc.bancoxyz.bff.cajero.dto.SaldoCajeroResponse;
import cl.duoc.bancoxyz.bff.cajero.service.RetiroService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Las dos únicas operaciones que expone el canal: consultar saldo y retirar.
 * No hay forma de pedir el historial ni los datos del titular desde un cajero.
 */
@RestController
@RequestMapping("/cajero/cuentas")
public class CuentaCajeroController {

    private final RetiroService servicio;

    public CuentaCajeroController(RetiroService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/{cuentaId}/saldo")
    public SaldoCajeroResponse consultarSaldo(@PathVariable Long cuentaId) {
        return servicio.consultarSaldo(cuentaId);
    }

    @PostMapping("/{cuentaId}/retiro")
    public ResponseEntity<RetiroResponse> retirar(@PathVariable Long cuentaId, @RequestBody RetiroRequest request) {
        RetiroResponse respuesta = servicio.retirar(cuentaId, request.monto());
        if (respuesta.aprobado()) {
            return ResponseEntity.ok(respuesta);
        }
        // Monto mal formado es un error de la petición; el resto son rechazos
        // de negocio sobre una petición bien formada.
        boolean peticionInvalida = request.monto() == null || request.monto().signum() <= 0;
        return peticionInvalida
                ? ResponseEntity.badRequest().body(respuesta)
                : ResponseEntity.unprocessableEntity().body(respuesta);
    }
}
