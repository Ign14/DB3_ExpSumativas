package cl.duoc.bancoxyz.bff.cajero.service;

import cl.duoc.bancoxyz.bff.cajero.dto.RetiroResponse;
import cl.duoc.bancoxyz.bff.cajero.dto.SaldoCajeroResponse;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.DebitoResponse;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los clientes hacia los servicios core se reemplazan por dobles escritos a
 * mano en vez de una librería de mocks: así los tests no dependen de
 * instrumentación de bytecode y corren igual en cualquier versión del JDK.
 */
class RetiroServiceTest {

    private static final Long CUENTA = 103L;
    private static final BigDecimal LIMITE = new BigDecimal("500000");

    private CuentasFalso cuentas;
    private MovimientosFalso movimientos;
    private RetiroService servicio;

    @BeforeEach
    void setUp() {
        cuentas = new CuentasFalso();
        movimientos = new MovimientosFalso();
        servicio = new RetiroService(cuentas, movimientos, LIMITE);
    }

    @Test
    @DisplayName("Un retiro aprobado debita el saldo y registra el movimiento en el otro servicio")
    void retiroAprobadoTocaLosDosServicios() {
        BigDecimal monto = new BigDecimal("500");
        cuentas.respuestaDebito = new DebitoResponse(CUENTA, monto, new BigDecimal("6500"), true, null);

        RetiroResponse respuesta = servicio.retirar(CUENTA, monto);

        assertTrue(respuesta.aprobado());
        assertTrue(respuesta.movimientoRegistrado());
        assertEquals(0, new BigDecimal("6500").compareTo(respuesta.saldoResultante()));
        assertEquals(1, cuentas.llamadasADebitar);
        assertEquals(0, monto.compareTo(cuentas.ultimoMontoDebitado));
        assertEquals(1, movimientos.movimientosRegistrados.size());

        MovimientoDTO registrado = movimientos.movimientosRegistrados.get(0);
        assertEquals(CUENTA, registrado.cuentaId());
        assertEquals("retiro", registrado.tipoMovimiento());
        assertEquals(0, monto.compareTo(registrado.monto()));
    }

    @Test
    @DisplayName("Si el registro del movimiento falla, el retiro sigue aprobado y lo informa el comprobante")
    void retiroAprobadoConRegistroFallido() {
        BigDecimal monto = new BigDecimal("500");
        cuentas.respuestaDebito = new DebitoResponse(CUENTA, monto, new BigDecimal("6500"), true, null);
        movimientos.fallarAlRegistrar = true;

        RetiroResponse respuesta = servicio.retirar(CUENTA, monto);

        assertTrue(respuesta.aprobado(), "el dinero ya salió de la cuenta: el retiro no se revierte");
        assertFalse(respuesta.movimientoRegistrado());
        assertEquals(0, new BigDecimal("6500").compareTo(respuesta.saldoResultante()));
    }

    @Test
    @DisplayName("Un monto sobre el límite del canal se rechaza sin llegar a los servicios core")
    void montoSobreLimiteNoLlegaAlCore() {
        RetiroResponse respuesta = servicio.retirar(CUENTA, new BigDecimal("600000"));

        assertFalse(respuesta.aprobado());
        assertTrue(respuesta.motivoRechazo().contains("límite máximo por operación"));
        assertEquals(0, cuentas.llamadasADebitar);
        assertTrue(movimientos.movimientosRegistrados.isEmpty());
    }

    @Test
    @DisplayName("Sin fondos suficientes se rechaza y no se registra ningún movimiento")
    void sinFondosNoRegistraMovimiento() {
        BigDecimal monto = new BigDecimal("400000");
        cuentas.respuestaDebito = new DebitoResponse(
                CUENTA, monto, new BigDecimal("6500"), false, "Fondos insuficientes");

        RetiroResponse respuesta = servicio.retirar(CUENTA, monto);

        assertFalse(respuesta.aprobado());
        assertEquals("Fondos insuficientes", respuesta.motivoRechazo());
        assertEquals(1, cuentas.llamadasADebitar);
        assertTrue(movimientos.movimientosRegistrados.isEmpty());
    }

    @Test
    @DisplayName("Un monto cero, negativo o ausente se rechaza sin llegar a los servicios core")
    void montoInvalido() {
        assertFalse(servicio.retirar(CUENTA, BigDecimal.ZERO).aprobado());
        assertFalse(servicio.retirar(CUENTA, new BigDecimal("-100")).aprobado());
        assertFalse(servicio.retirar(CUENTA, null).aprobado());

        assertEquals(0, cuentas.llamadasADebitar);
        assertTrue(movimientos.movimientosRegistrados.isEmpty());
    }

    @Test
    @DisplayName("La consulta de saldo no expone datos del titular")
    void consultaDeSaldoSoloDevuelveSaldo() {
        cuentas.cuenta = new CuentaDTO(CUENTA, "Bob Johnson", 30, "ahorro", new BigDecimal("7000"));

        SaldoCajeroResponse saldo = servicio.consultarSaldo(CUENTA);

        assertEquals(CUENTA, saldo.cuentaId());
        assertEquals(0, new BigDecimal("7000").compareTo(saldo.saldoDisponible()));
    }

    // --- Dobles de prueba -------------------------------------------------

    private static class CuentasFalso extends CuentasApiClient {

        CuentaDTO cuenta;
        DebitoResponse respuestaDebito;
        int llamadasADebitar;
        BigDecimal ultimoMontoDebitado;

        CuentasFalso() {
            super(null);
        }

        @Override
        public CuentaDTO obtenerCuenta(Long cuentaId) {
            return cuenta;
        }

        @Override
        public DebitoResponse debitar(Long cuentaId, BigDecimal monto) {
            llamadasADebitar++;
            ultimoMontoDebitado = monto;
            return respuestaDebito;
        }
    }

    private static class MovimientosFalso extends MovimientosApiClient {

        final java.util.List<MovimientoDTO> movimientosRegistrados = new java.util.ArrayList<>();
        boolean fallarAlRegistrar;

        MovimientosFalso() {
            super(null);
        }

        @Override
        public MovimientoDTO registrarMovimiento(MovimientoDTO movimiento) {
            if (fallarAlRegistrar) {
                throw new ServicioCoreNoDisponibleException("core-movimientos-service");
            }
            movimientosRegistrados.add(movimiento);
            return movimiento;
        }
    }
}
