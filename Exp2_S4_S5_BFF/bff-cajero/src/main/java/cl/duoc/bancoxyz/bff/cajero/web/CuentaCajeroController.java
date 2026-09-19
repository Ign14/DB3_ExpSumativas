package cl.duoc.bancoxyz.bff.cajero.web;

import cl.duoc.bancoxyz.bff.cajero.dto.RetiroRequest;
import cl.duoc.bancoxyz.bff.cajero.dto.RetiroResponse;
import cl.duoc.bancoxyz.bff.cajero.dto.SaldoCajeroResponse;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.DebitoResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Solo dos operaciones, ambas "criticas" en el sentido del enunciado:
 * consultar saldo y retirar. El limite maximo por operacion es una regla
 * propia de este canal (no existe en core-cuentas-service, que solo conoce
 * la regla de negocio general "no dejar el saldo negativo").
 */
@RestController
@RequestMapping("/cajero/cuentas")
public class CuentaCajeroController {

    /** Regla exclusiva del canal cajero: un monto maximo por operacion. */
    private static final BigDecimal LIMITE_RETIRO_POR_OPERACION = new BigDecimal("500000");

    private final CuentasApiClient cuentasApiClient;

    public CuentaCajeroController(CuentasApiClient cuentasApiClient) {
        this.cuentasApiClient = cuentasApiClient;
    }

    @GetMapping("/{cuentaId}/saldo")
    public SaldoCajeroResponse consultarSaldo(@PathVariable Long cuentaId) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        return new SaldoCajeroResponse(cuenta.cuentaId(), cuenta.saldo());
    }

    @PostMapping("/{cuentaId}/retiro")
    public ResponseEntity<RetiroResponse> retirar(@PathVariable Long cuentaId, @RequestBody RetiroRequest request) {
        BigDecimal monto = request.monto();
        if (monto == null || monto.signum() <= 0) {
            return ResponseEntity.badRequest().body(
                    new RetiroResponse(cuentaId, monto, null, false, "El monto a retirar debe ser mayor que cero"));
        }
        if (monto.compareTo(LIMITE_RETIRO_POR_OPERACION) > 0) {
            return ResponseEntity.unprocessableEntity().body(new RetiroResponse(
                    cuentaId, monto, null, false,
                    "Excede el limite maximo por operacion en cajero ($" + LIMITE_RETIRO_POR_OPERACION + ")"));
        }

        DebitoResponse resultado = cuentasApiClient.debitar(cuentaId, monto);
        RetiroResponse respuesta = new RetiroResponse(
                cuentaId, monto, resultado.saldoResultante(), resultado.aprobado(), resultado.motivoRechazo());
        return resultado.aprobado() ? ResponseEntity.ok(respuesta) : ResponseEntity.unprocessableEntity().body(respuesta);
    }
}
