package cl.duoc.bancoxyz.pagos.service;

import cl.duoc.bancoxyz.common.dto.DepositoRequest;
import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.dto.OperacionResponse;
import cl.duoc.bancoxyz.common.dto.TransferenciaRequest;
import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.pagos.cliente.LiquidacionClienteResiliente;
import cl.duoc.bancoxyz.pagos.cliente.ResultadoLiquidacion;
import cl.duoc.bancoxyz.pagos.domain.MovimientoRepositoryEnMemoria;
import cl.duoc.bancoxyz.pagos.mensajeria.PublicadorEventosKafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Procesamiento de pagos: depositos y transferencias.
 *
 * Este servicio no es dueno del saldo. El saldo vive en cuentas-service y aqui
 * se orquesta: se le pide mover las puntas, se anota el movimiento en el
 * historial propio y se publica el hecho. Esa separacion es la que obliga a
 * hablar de consistencia, porque una transferencia son dos llamadas remotas y
 * no existe una transaccion que abarque a las dos.
 *
 * Lo que se hace al respecto, en orden de importancia:
 *
 * 1. **Se carga antes de abonar.** Si falla la primera punta, no hay nada que
 *    deshacer. Al revés —abonar y luego cargar— un fallo en la segunda dejaria
 *    dinero creado, que es el error mas caro de los dos.
 * 2. **Se compensa la punta aplicada.** Si el abono al destino falla, se
 *    devuelve el monto a la cuenta de origen. Es una compensacion de negocio, no
 *    un rollback: queda rastro de las tres operaciones.
 * 3. **Si la compensacion tambien falla, se grita.** Ahi hay un descalce real y
 *    lo unico honesto es dejarlo registrado con severidad alta y publicarlo en
 *    el topico, en vez de que el sistema siga como si nada.
 *
 * Lo que falta para cerrarlo del todo esta dicho en el informe tecnico: una
 * clave de idempotencia por operacion, que permitiria reintentar sin miedo a
 * duplicar, y un registro durable de operaciones en curso para poder retomar la
 * compensacion si este proceso se cae en el peor momento. Con estado en memoria
 * no se puede sostener ninguna de las dos, y no se finge que si.
 */
@Service
public class PagoService {

    private static final Logger log = LoggerFactory.getLogger(PagoService.class);

    private final LiquidacionClienteResiliente liquidacion;
    private final MovimientoRepositoryEnMemoria repositorio;
    private final PublicadorEventosKafka publicador;
    private final BigDecimal limitePorOperacion;

    public PagoService(LiquidacionClienteResiliente liquidacion,
                       MovimientoRepositoryEnMemoria repositorio,
                       PublicadorEventosKafka publicador,
                       @Value("${banco.pagos.limite-por-operacion}") BigDecimal limitePorOperacion) {
        this.liquidacion = liquidacion;
        this.repositorio = repositorio;
        this.publicador = publicador;
        this.limitePorOperacion = limitePorOperacion;
    }

    public OperacionResponse depositar(Long cuentaId, DepositoRequest solicitud) {
        BigDecimal monto = validarMonto(solicitud == null ? null : solicitud.monto());
        String canal = canal(solicitud == null ? null : solicitud.canal());
        String hoy = hoy();
        String operacionId = UUID.randomUUID().toString();

        if (monto.compareTo(limitePorOperacion) > 0) {
            return rechazo(operacionId, TransaccionCompletadaEvento.DEPOSITO, cuentaId, null, monto,
                    "El monto supera el limite de " + limitePorOperacion.toPlainString() + " por operacion.");
        }

        BigDecimal saldoResultante = liquidacion.abonar(cuentaId, monto);

        String descripcion = descripcion(solicitud == null ? null : solicitud.descripcion(),
                "Deposito por canal " + canal);
        repositorio.registrar(new MovimientoDTO(cuentaId, hoy, "deposito", monto, descripcion));

        boolean publicado = publicador.publicarTransaccion(new TransaccionCompletadaEvento(
                operacionId, TransaccionCompletadaEvento.DEPOSITO,
                cuentaId, cuentaId, monto, saldoResultante, hoy, canal));

        log.info(">> pagos-service: deposito {} de {} aplicado en la cuenta {} - saldo {}",
                operacionId, monto, cuentaId, saldoResultante);
        return new OperacionResponse(operacionId, TransaccionCompletadaEvento.DEPOSITO,
                cuentaId, cuentaId, monto, saldoResultante, true, null, publicado);
    }

    public OperacionResponse transferir(Long cuentaOrigen, TransferenciaRequest solicitud) {
        BigDecimal monto = validarMonto(solicitud == null ? null : solicitud.monto());
        Long cuentaDestino = solicitud == null ? null : solicitud.cuentaDestino();
        if (cuentaDestino == null) {
            throw new ParametroInvalidoException("Debe indicar la cuenta de destino.");
        }
        if (cuentaDestino.equals(cuentaOrigen)) {
            // Permitirlo no romperia nada, pero una transferencia a si mismo es
            // casi siempre un error de quien llama, y aceptarla en silencio
            // dejaria dos movimientos en el historial que no significan nada.
            throw new ParametroInvalidoException("La cuenta de destino debe ser distinta de la de origen.");
        }
        String canal = canal(solicitud.canal());
        String hoy = hoy();
        String operacionId = UUID.randomUUID().toString();

        if (monto.compareTo(limitePorOperacion) > 0) {
            return rechazo(operacionId, TransaccionCompletadaEvento.TRANSFERENCIA,
                    cuentaOrigen, cuentaDestino, monto,
                    "El monto supera el limite de " + limitePorOperacion.toPlainString() + " por operacion.");
        }

        ResultadoLiquidacion cargo = liquidacion.cargar(cuentaOrigen, monto);
        if (!cargo.aplicado()) {
            log.info(">> pagos-service: transferencia {} rechazada en el origen {} por {}",
                    operacionId, cuentaOrigen, cargo.motivoRechazo());
            return rechazo(operacionId, TransaccionCompletadaEvento.TRANSFERENCIA,
                    cuentaOrigen, cuentaDestino, monto, cargo.motivoRechazo());
        }

        try {
            liquidacion.abonar(cuentaDestino, monto);
        } catch (RuntimeException ex) {
            compensar(operacionId, cuentaOrigen, cuentaDestino, monto, hoy, ex);
            throw ex;
        }

        String descripcion = descripcion(solicitud.descripcion(),
                "Transferencia por canal " + canal);
        repositorio.registrar(new MovimientoDTO(cuentaOrigen, hoy, "pago", monto,
                descripcion + " hacia la cuenta " + cuentaDestino));
        repositorio.registrar(new MovimientoDTO(cuentaDestino, hoy, "deposito", monto,
                descripcion + " desde la cuenta " + cuentaOrigen));

        boolean publicado = publicador.publicarTransaccion(new TransaccionCompletadaEvento(
                operacionId, TransaccionCompletadaEvento.TRANSFERENCIA,
                cuentaOrigen, cuentaDestino, monto, cargo.saldoResultante(), hoy, canal));

        log.info(">> pagos-service: transferencia {} de {} aplicada de la cuenta {} a la {} - saldo origen {}",
                operacionId, monto, cuentaOrigen, cuentaDestino, cargo.saldoResultante());
        return new OperacionResponse(operacionId, TransaccionCompletadaEvento.TRANSFERENCIA,
                cuentaOrigen, cuentaDestino, monto, cargo.saldoResultante(), true, null, publicado);
    }

    /**
     * Devuelve al origen el monto que ya se le habia descontado.
     *
     * Si esto tambien falla, el sistema queda con un descalce que no se resuelve
     * solo: el dinero salio de una cuenta y no llego a ninguna. La respuesta
     * correcta no es reintentar en un bucle ni tragarse el error, sino dejarlo
     * registrado con todos los datos necesarios para reponerlo a mano y
     * publicarlo con severidad alta.
     */
    private void compensar(String operacionId, Long cuentaOrigen, Long cuentaDestino,
                           BigDecimal monto, String fecha, RuntimeException causaOriginal) {
        log.warn(">> pagos-service: transferencia {} fallo al abonar la cuenta {} por {} - "
                        + "compensando el cargo de {} en la cuenta {}",
                operacionId, cuentaDestino, causaOriginal.getClass().getSimpleName(), monto, cuentaOrigen);
        try {
            BigDecimal saldo = liquidacion.abonar(cuentaOrigen, monto);
            log.info(">> pagos-service: transferencia {} compensada - la cuenta {} recupero {} y queda en {}",
                    operacionId, cuentaOrigen, monto, saldo);
            repositorio.registrar(new MovimientoDTO(cuentaOrigen, fecha, "deposito", monto,
                    "Reverso de la transferencia " + operacionId + " hacia la cuenta " + cuentaDestino));
        } catch (RuntimeException fallaCompensacion) {
            log.error(">> pagos-service: DESCALCE en la transferencia {} - se cargaron {} a la cuenta {}, "
                            + "no se abonaron a la cuenta {} y la compensacion tambien fallo por {}. "
                            + "Requiere reposicion manual.",
                    operacionId, monto, cuentaOrigen, cuentaDestino,
                    fallaCompensacion.getClass().getSimpleName());
            publicador.publicarAlerta(new AlertaSeguridadEvento(
                    UUID.randomUUID().toString(),
                    AlertaSeguridadEvento.COMPENSACION_FALLIDA,
                    cuentaOrigen,
                    "Transferencia " + operacionId + " por " + monto.toPlainString()
                            + " hacia la cuenta " + cuentaDestino
                            + ": cargo aplicado, abono fallido y compensacion fallida.",
                    AlertaSeguridadEvento.SEVERIDAD_ALTA,
                    fecha));
        }
    }

    private OperacionResponse rechazo(String operacionId, String tipo, Long origen, Long destino,
                                      BigDecimal monto, String motivo) {
        return new OperacionResponse(operacionId, tipo, origen, destino, monto, null, false, motivo, false);
    }

    private static BigDecimal validarMonto(BigDecimal monto) {
        if (monto == null) {
            throw new ParametroInvalidoException("Debe indicar el monto de la operacion.");
        }
        if (monto.signum() <= 0) {
            throw new ParametroInvalidoException("El monto debe ser mayor que cero.");
        }
        return monto;
    }

    private static String canal(String canal) {
        return (canal == null || canal.isBlank()) ? "no-informado" : canal.trim();
    }

    private static String descripcion(String recibida, String porDefecto) {
        if (recibida == null || recibida.isBlank()) {
            return porDefecto;
        }
        String limpia = recibida.trim();
        return limpia.length() > 120 ? limpia.substring(0, 120) : limpia;
    }

    private static String hoy() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
