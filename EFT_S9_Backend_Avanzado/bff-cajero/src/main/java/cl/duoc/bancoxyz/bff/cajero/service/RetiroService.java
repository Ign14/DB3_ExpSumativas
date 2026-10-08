package cl.duoc.bancoxyz.bff.cajero.service;

import cl.duoc.bancoxyz.bff.cajero.dto.ComprobanteRetiro;
import cl.duoc.bancoxyz.bff.cajero.dto.SaldoCajeroResponse;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Operaciones del canal cajero.
 *
 * Un retiro es ahora una sola llamada. En la version de la semana 5 este canal
 * tenia que debitar en un servicio y registrar el movimiento en el otro, y
 * cargaba con el problema de que la segunda llamada podia fallar despues de que
 * el dinero ya habia salido. Con la arquitectura de eventos esa orquestacion
 * dejo de ser suya: cuentas-service publica el retiro en la cola y pagos-service
 * lo anota en el historial, con la cola garantizando que el evento espere si el
 * consumidor esta caido. El canal se quedo solo con lo que le corresponde, que
 * es su limite por operacion y la forma del comprobante.
 */
@Service
public class RetiroService {

    private static final Logger log = LoggerFactory.getLogger(RetiroService.class);
    /** Identifica al canal en el evento que se publica, para poder medirlo despues. */
    private static final String CANAL = "cajero";

    private final CuentasApiClient cuentasApiClient;
    private final BigDecimal limitePorOperacion;

    public RetiroService(CuentasApiClient cuentasApiClient,
                         @Value("${bff.cajero.limite-retiro-por-operacion}") BigDecimal limitePorOperacion) {
        this.cuentasApiClient = cuentasApiClient;
        this.limitePorOperacion = limitePorOperacion;
    }

    public SaldoCajeroResponse consultarSaldo(Long cuentaId) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        return new SaldoCajeroResponse(cuenta.cuentaId(), cuenta.saldo());
    }

    /**
     * El monto pasa por tres validaciones en capas distintas, y el orden no es
     * casual: el limite de este canal se aplica aqui y ahorra una llamada
     * remota; el limite del dominio lo aplica cuentas-service, que es quien
     * puede cambiarlo por configuracion; y la suficiencia de fondos la decide la
     * cuenta, dentro de la operacion atomica que mueve el saldo.
     *
     * Que el canal tenga un limite propio mas bajo que el del dominio no es
     * redundante: un cajero dispensa billetes y tiene restricciones fisicas que
     * la cuenta no conoce.
     */
    public ComprobanteRetiro retirar(Long cuentaId, BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            return ComprobanteRetiro.rechazado(cuentaId, monto,
                    "El monto a retirar debe ser mayor que cero", null);
        }
        if (monto.compareTo(limitePorOperacion) > 0) {
            return ComprobanteRetiro.rechazado(cuentaId, monto,
                    "Excede el límite máximo por operación en cajero ($" + limitePorOperacion.toPlainString() + ")",
                    null);
        }

        RetiroResponse retiro = cuentasApiClient.retirar(cuentaId, monto, CANAL);
        if (!retiro.aprobado()) {
            return ComprobanteRetiro.rechazado(cuentaId, monto, retiro.motivoRechazo(), retiro.saldoResultante());
        }

        String fecha = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (!retiro.eventoPublicado()) {
            // El dinero salio pero el evento no: el movimiento no va a aparecer
            // en el historial hasta que alguien lo reponga. No se revierte el
            // retiro, porque ya ocurrio, y no se devuelve un error, porque la
            // operacion fue exitosa. Se deja en el log y se marca en el
            // comprobante para que el titular no quede preguntandose por que su
            // retiro no figura.
            log.error("Retiro aplicado en la cuenta {} por {} pero el evento {} no se pudo publicar: "
                            + "el movimiento no llegara al historial sin reposicion manual",
                    cuentaId, monto, retiro.eventoId());
        }
        return ComprobanteRetiro.aprobado(cuentaId, monto, retiro.saldoResultante(),
                fecha, retiro.eventoPublicado());
    }
}
