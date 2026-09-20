package cl.duoc.bancoxyz.bff.movil.service;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.exception.ParametroInvalidoException;
import cl.duoc.bancoxyz.bff.movil.dto.CuentaMovilResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class CuentaMovilServiceTest {

    private static final Long CUENTA = 103L;

    private CuentasApiClient cuentas;
    private MovimientosApiClient movimientos;
    private CuentaMovilService servicio;

    @BeforeEach
    void setUp() {
        cuentas = mock(CuentasApiClient.class);
        movimientos = mock(MovimientosApiClient.class);
        servicio = new CuentaMovilService(cuentas, movimientos);

        when(cuentas.obtenerCuenta(CUENTA)).thenReturn(
                new CuentaDTO(CUENTA, "Bob Johnson", 30, "ahorro", new BigDecimal("7000")));
        when(movimientos.obtenerUltimosMovimientos(anyLong(), anyInt())).thenReturn(List.of(
                new MovimientoDTO(CUENTA, "2024-12-23", "retiro", new BigDecimal("2500"), "Sin descripción")));
    }

    @Test
    @DisplayName("La respuesta omite el nombre del titular y la descripción de los movimientos")
    void respuestaOmiteCamposPesados() {
        CuentaMovilResponse respuesta = servicio.obtenerResumenLiviano(CUENTA, 5);

        assertEquals(0, new BigDecimal("7000").compareTo(respuesta.saldo()));
        assertEquals("ahorro", respuesta.tipoCuenta());
        assertEquals(1, respuesta.ultimosMovimientos().size());
        assertEquals("retiro", respuesta.ultimosMovimientos().get(0).tipo());
    }

    @Test
    @DisplayName("El recorte se le pide al servicio core, no se hace después en el BFF")
    void elRecorteSePideAlCore() {
        servicio.obtenerResumenLiviano(CUENTA, 3);

        verify(movimientos).obtenerUltimosMovimientos(CUENTA, 3);
        verify(movimientos, never()).obtenerMovimientos(anyLong());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, 999})
    @DisplayName("Un limite fuera de rango se rechaza antes de llamar a los servicios core")
    void limiteFueraDeRango(int limite) {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.obtenerResumenLiviano(CUENTA, limite));
        verifyNoInteractions(cuentas, movimientos);
    }
}
