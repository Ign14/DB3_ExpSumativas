package cl.duoc.bancoxyz.bff.cajero.web;

import cl.duoc.bancoxyz.bff.cajero.dto.RetiroRequest;
import cl.duoc.bancoxyz.bff.cajero.dto.RetiroResponse;
import cl.duoc.bancoxyz.bff.cajero.dto.SaldoCajeroResponse;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.DebitoResponse;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Solo dos operaciones, ambas "criticas" en el sentido del enunciado:
 * consultar saldo y retirar. El retiro integra los dos servicios core:
 * debita el saldo en core-cuentas-service y registra el movimiento en
 * core-movimientos-service, de modo que el retiro hecho en el cajero sea
 * despues visible en el historial que muestran los canales web y movil.
 */
@RestController
@RequestMapping("/cajero/cuentas")
public class CuentaCajeroController {

    private static final Logger log = LoggerFactory.getLogger(CuentaCajeroController.class);
    private static final String DESCRIPCION_RETIRO = "Retiro por cajero automático";

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;

    /** Regla exclusiva del canal cajero, configurable sin recompilar. */
    private final BigDecimal limiteRetiroPorOperacion;

    public CuentaCajeroController(CuentasApiClient cuentasApiClient,
                                  MovimientosApiClient movimientosApiClient,
                                  @Value("${bff.cajero.limite-retiro-por-operacion}") BigDecimal limiteRetiroPorOperacion) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
        this.limiteRetiroPorOperacion = limiteRetiroPorOperacion;
    }

    @GetMapping("/{cuentaId}/saldo")
    public SaldoCajeroResponse consultarSaldo(@PathVariable Long cuentaId) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        return new SaldoCajeroResponse(cuenta.cuentaId(), cuenta.saldo());
    }

    @PostMapping("/{cuentaId}/retiro")
    public ResponseEntity<RetiroResponse> retirar(@PathVariable Long cuentaId, @RequestBody RetiroRequest request) {
        BigDecimal monto = request.monto();

        // 1. Regla del canal: monto valido y dentro del limite por operacion.
        if (monto == null || monto.signum() <= 0) {
            return ResponseEntity.badRequest().body(RetiroResponse.rechazado(
                    cuentaId, monto, "El monto a retirar debe ser mayor que cero", null));
        }
        if (monto.compareTo(limiteRetiroPorOperacion) > 0) {
            return ResponseEntity.unprocessableEntity().body(RetiroResponse.rechazado(
                    cuentaId, monto,
                    "Excede el limite maximo por operacion en cajero ($" + limiteRetiroPorOperacion.toPlainString() + ")",
                    null));
        }

        // 2. Regla del dominio: core-cuentas-service decide si hay fondos.
        DebitoResponse debito = cuentasApiClient.debitar(cuentaId, monto);
        if (!debito.aprobado()) {
            return ResponseEntity.unprocessableEntity().body(RetiroResponse.rechazado(
                    cuentaId, monto, debito.motivoRechazo(), debito.saldoResultante()));
        }

        // 3. Ya debitado: se registra el movimiento en el otro servicio core.
        String fecha = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        boolean registrado = registrarMovimiento(cuentaId, monto, fecha);

        return ResponseEntity.ok(RetiroResponse.aprobado(
                cuentaId, monto, debito.saldoResultante(), fecha, registrado));
    }

    /**
     * El dinero ya salio de la cuenta, asi que un fallo al registrar el
     * movimiento no puede invalidar el retiro: se deja constancia en el log
     * y se marca en el comprobante, en vez de devolverle un error al cajero
     * por algo que ya ocurrio.
     */
    private boolean registrarMovimiento(Long cuentaId, BigDecimal monto, String fecha) {
        try {
            movimientosApiClient.registrarMovimiento(
                    new MovimientoDTO(cuentaId, fecha, "retiro", monto, DESCRIPCION_RETIRO));
            return true;
        } catch (ServicioCoreNoDisponibleException ex) {
            log.error("Retiro aplicado en la cuenta {} por {} pero no se pudo registrar el movimiento: {}",
                    cuentaId, monto, ex.getMessage());
            return false;
        }
    }
}
