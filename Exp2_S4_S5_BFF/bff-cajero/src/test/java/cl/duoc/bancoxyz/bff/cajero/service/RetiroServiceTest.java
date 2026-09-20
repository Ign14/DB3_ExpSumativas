package cl.duoc.bancoxyz.bff.cajero.service;

import cl.duoc.bancoxyz.bff.cajero.dto.RetiroResponse;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.DebitoResponse;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RetiroServiceTest {

    private static final Long CUENTA = 103L;
    private static final BigDecimal LIMITE = new BigDecimal("500000");

    private CuentasApiClient cuentas;
    private MovimientosApiClient movimientos;
    private RetiroService servicio;

    @BeforeEach
    void setUp() {
        cuentas = mock(CuentasApiClient.class);
        movimientos = mock(MovimientosApiClient.class);
        servicio = new RetiroService(cuentas, movimientos, LIMITE);
    }

    @Test
    @DisplayName("Un retiro aprobado debita el saldo y registra el movimiento en el otro servicio")
    void retiroAprobadoTocaLosDosServicios() {
        BigDecimal monto = new BigDecimal("500");
        when(cuentas.debitar(CUENTA, monto))
                .thenReturn(new DebitoResponse(CUENTA, monto, new BigDecimal("6500"), true, null));

        RetiroResponse respuesta = servicio.retirar(CUENTA, monto);

        assertTrue(respuesta.aprobado());
        assertTrue(respuesta.movimientoRegistrado());
        assertEquals(0, new BigDecimal("6500").compareTo(respuesta.saldoResultante()));
        verify(cuentas).debitar(CUENTA, monto);
        verify(movimientos).registrarMovimiento(any(MovimientoDTO.class));
    }

    @Test
    @DisplayName("Si el registro del movimiento falla, el retiro sigue aprobado y lo informa el comprobante")
    void retiroAprobadoConRegistroFallido() {
        BigDecimal monto = new BigDecimal("500");
        when(cuentas.debitar(CUENTA, monto))
                .thenReturn(new DebitoResponse(CUENTA, monto, new BigDecimal("6500"), true, null));
        when(movimientos.registrarMovimiento(any(MovimientoDTO.class)))
                .thenThrow(new ServicioCoreNoDisponibleException("core-movimientos-service"));

        RetiroResponse respuesta = servicio.retirar(CUENTA, monto);

        assertTrue(respuesta.aprobado(), "el dinero ya salió: el retiro no se revierte");
        assertFalse(respuesta.movimientoRegistrado());
    }

    @Test
    @DisplayName("Un monto sobre el límite del canal se rechaza sin llegar a los servicios core")
    void montoSobreLimiteNoLlegaAlCore() {
        RetiroResponse respuesta = servicio.retirar(CUENTA, new BigDecimal("600000"));

        assertFalse(respuesta.aprobado());
        assertTrue(respuesta.motivoRechazo().contains("límite máximo por operación"));
        verifyNoInteractions(cuentas, movimientos);
    }

    @Test
    @DisplayName("Sin fondos suficientes se rechaza y no se registra ningún movimiento")
    void sinFondosNoRegistraMovimiento() {
        BigDecimal monto = new BigDecimal("400000");
        when(cuentas.debitar(CUENTA, monto)).thenReturn(
                new DebitoResponse(CUENTA, monto, new BigDecimal("6500"), false, "Fondos insuficientes"));

        RetiroResponse respuesta = servicio.retirar(CUENTA, monto);

        assertFalse(respuesta.aprobado());
        assertEquals("Fondos insuficientes", respuesta.motivoRechazo());
        verify(movimientos, never()).registrarMovimiento(any());
    }

    @Test
    @DisplayName("Un monto cero o negativo se rechaza sin llegar a los servicios core")
    void montoInvalido() {
        assertFalse(servicio.retirar(CUENTA, BigDecimal.ZERO).aprobado());
        assertFalse(servicio.retirar(CUENTA, new BigDecimal("-100")).aprobado());
        assertFalse(servicio.retirar(CUENTA, null).aprobado());
        verifyNoInteractions(cuentas, movimientos);
    }

    @Test
    @DisplayName("La consulta de saldo no expone datos del titular")
    void consultaDeSaldoSoloDevuelveSaldo() {
        when(cuentas.obtenerCuenta(eq(CUENTA))).thenReturn(
                new cl.duoc.bancoxyz.bff.common.dto.CuentaDTO(
                        CUENTA, "Bob Johnson", 30, "ahorro", new BigDecimal("7000")));

        var saldo = servicio.consultarSaldo(CUENTA);

        assertEquals(CUENTA, saldo.cuentaId());
        assertEquals(0, new BigDecimal("7000").compareTo(saldo.saldoDisponible()));
    }
}
