package cl.duoc.bancoxyz.bff.cajero.service;

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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Operaciones del canal cajero. Un retiro toca los dos servicios core: debita
 * el saldo en core-cuentas-service y deja el movimiento registrado en
 * core-movimientos-service, de modo que la operación sea visible después desde
 * los canales web y móvil.
 */
@Service
public class RetiroService {

    private static final Logger log = LoggerFactory.getLogger(RetiroService.class);
    private static final String DESCRIPCION_RETIRO = "Retiro por cajero automático";

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;
    private final BigDecimal limitePorOperacion;

    public RetiroService(CuentasApiClient cuentasApiClient,
                         MovimientosApiClient movimientosApiClient,
                         @Value("${bff.cajero.limite-retiro-por-operacion}") BigDecimal limitePorOperacion) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
        this.limitePorOperacion = limitePorOperacion;
    }

    public SaldoCajeroResponse consultarSaldo(Long cuentaId) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        return new SaldoCajeroResponse(cuenta.cuentaId(), cuenta.saldo());
    }

    /**
     * El monto pasa por dos validaciones en capas distintas: el límite por
     * operación es una regla de este canal y se aplica aquí, mientras que la
     * suficiencia de fondos es una invariante de la cuenta y la decide
     * core-cuentas-service.
     */
    public RetiroResponse retirar(Long cuentaId, BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            return RetiroResponse.rechazado(cuentaId, monto,
                    "El monto a retirar debe ser mayor que cero", null);
        }
        if (monto.compareTo(limitePorOperacion) > 0) {
            return RetiroResponse.rechazado(cuentaId, monto,
                    "Excede el límite máximo por operación en cajero ($" + limitePorOperacion.toPlainString() + ")",
                    null);
        }

        DebitoResponse debito = cuentasApiClient.debitar(cuentaId, monto);
        if (!debito.aprobado()) {
            return RetiroResponse.rechazado(cuentaId, monto, debito.motivoRechazo(), debito.saldoResultante());
        }

        String fecha = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        boolean registrado = registrarMovimiento(cuentaId, monto, fecha);
        return RetiroResponse.aprobado(cuentaId, monto, debito.saldoResultante(), fecha, registrado);
    }

    /**
     * Si el registro falla, el retiro no se revierte: el dinero ya salió de la
     * cuenta, así que devolver un error sería informar mal al cajero sobre algo
     * que ya ocurrió. Queda en el log y marcado en el comprobante.
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
