package cl.duoc.bancoxyz.cuentas.service;

import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.cuentas.domain.CuentaRepositoryEnMemoria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Operaciones primitivas sobre el saldo: cargar y abonar.
 *
 * Son distintas del retiro y la distincion es deliberada. Un retiro es una
 * operacion de negocio del dominio de cuentas: la pide un canal, tiene limite
 * configurable y publica el hecho de que ocurrio. Un cargo o un abono, en
 * cambio, son la liquidacion de una operacion cuyo dueno es otro servicio:
 * pagos-service decide que hay una transferencia, y le pide a este servicio que
 * mueva las dos puntas.
 *
 * De ahi que estas dos operaciones no publiquen ningun evento. Si lo hicieran,
 * una transferencia aparecería en el topico como un cargo y un abono sueltos,
 * ademas del evento de transferencia que publica su dueno: tres hechos para una
 * sola operacion, y ningun consumidor podria saber que los tres son lo mismo.
 * El que publica es el que sabe que paso.
 *
 * Por el mismo motivo exigen un scope propio ({@code cuentas.liquidar}) y no el
 * {@code cuentas.write} del retiro. Un cajero automatico puede retirar, y eso
 * pasa por el limite por operacion y queda en el topico; no deberia poder
 * mover saldo sin dejar rastro llamando directo a la primitiva.
 */
@Service
public class LiquidacionService {

    private static final Logger log = LoggerFactory.getLogger(LiquidacionService.class);

    private final CuentaRepositoryEnMemoria repositorio;

    public LiquidacionService(CuentaRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    /** Abona el monto y devuelve el saldo resultante. */
    public BigDecimal abonar(Long cuentaId, BigDecimal monto) {
        validarMonto(monto);
        BigDecimal saldo = repositorio.abonar(cuentaId, monto)
                .orElseThrow(() -> new RecursoNoEncontradoException("La cuenta " + cuentaId + " no existe."));
        log.info(">> cuentas-service: abono de {} en la cuenta {} - saldo resultante {}",
                monto, cuentaId, saldo);
        return saldo;
    }

    /**
     * Carga el monto si hay fondos. Devuelve vacio si no los hay, en vez de
     * lanzar: para quien orquesta una transferencia, "no habia saldo" es una
     * respuesta esperada que tiene que poder manejar, no una condicion
     * excepcional.
     */
    public Optional<BigDecimal> cargar(Long cuentaId, BigDecimal monto) {
        validarMonto(monto);
        CuentaRepositoryEnMemoria.ResultadoDebito debito = repositorio.debitar(cuentaId, monto)
                .orElseThrow(() -> new RecursoNoEncontradoException("La cuenta " + cuentaId + " no existe."));
        if (!debito.aprobado()) {
            log.info(">> cuentas-service: cargo de {} rechazado en la cuenta {} por {}",
                    monto, cuentaId, debito.motivoRechazo());
            return Optional.empty();
        }
        log.info(">> cuentas-service: cargo de {} en la cuenta {} - saldo resultante {}",
                monto, cuentaId, debito.saldoResultante());
        return Optional.of(debito.saldoResultante());
    }

    private static void validarMonto(BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new ParametroInvalidoException("El monto debe ser mayor que cero.");
        }
    }
}
