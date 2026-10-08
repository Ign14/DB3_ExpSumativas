package cl.duoc.bancoxyz.pagos.web;

import cl.duoc.bancoxyz.common.dto.DepositoRequest;
import cl.duoc.bancoxyz.common.dto.OperacionResponse;
import cl.duoc.bancoxyz.common.dto.TransferenciaRequest;
import cl.duoc.bancoxyz.pagos.service.PagoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de procesamiento de pagos.
 *
 * Igual que en el resto del sistema, una operacion rechazada por reglas de
 * negocio responde 200 con el motivo en el comprobante: la peticion estaba bien
 * formada y el servicio la evaluo. Los 4xx quedan para peticiones mal hechas y
 * los 5xx para cuando el sistema no pudo decidir.
 */
@RestController
@RequestMapping("/pagos")
public class PagoController {

    private final PagoService pagoService;

    public PagoController(PagoService pagoService) {
        this.pagoService = pagoService;
    }

    @PostMapping("/deposito/{cuentaId}")
    public ResponseEntity<OperacionResponse> depositar(@PathVariable Long cuentaId,
                                                       @RequestBody DepositoRequest solicitud) {
        return ResponseEntity.ok(pagoService.depositar(cuentaId, solicitud));
    }

    @PostMapping("/transferencia/{cuentaOrigen}")
    public ResponseEntity<OperacionResponse> transferir(@PathVariable Long cuentaOrigen,
                                                        @RequestBody TransferenciaRequest solicitud) {
        return ResponseEntity.ok(pagoService.transferir(cuentaOrigen, solicitud));
    }
}
