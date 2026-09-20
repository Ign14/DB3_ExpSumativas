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

/**
 * Los clientes hacia los servicios core se reemplazan por dobles escritos a
 * mano en vez de una librería de mocks: así los tests no dependen de
 * instrumentación de bytecode y corren igual en cualquier versión del JDK.
 */
class CuentaMovilServiceTest {

    private static final Long CUENTA = 103L;

    private CuentasFalso cuentas;
    private MovimientosFalso movimientos;
    private CuentaMovilService servicio;

    @BeforeEach
    void setUp() {
        cuentas = new CuentasFalso();
        cuentas.cuenta = new CuentaDTO(CUENTA, "Bob Johnson", 30, "ahorro", new BigDecimal("7000"));

        movimientos = new MovimientosFalso();
        movimientos.ultimos = List.of(
                new MovimientoDTO(CUENTA, "2024-12-23", "retiro", new BigDecimal("2500"), "Sin descripción"));

        servicio = new CuentaMovilService(cuentas, movimientos);
    }

    @Test
    @DisplayName("La respuesta omite el nombre del titular y la descripción de los movimientos")
    void respuestaOmiteCamposPesados() {
        CuentaMovilResponse respuesta = servicio.obtenerResumenLiviano(CUENTA, 5);

        assertEquals(0, new BigDecimal("7000").compareTo(respuesta.saldo()));
        assertEquals("ahorro", respuesta.tipoCuenta());
        assertEquals(1, respuesta.ultimosMovimientos().size());
        assertEquals("retiro", respuesta.ultimosMovimientos().get(0).tipo());
        assertEquals("2024-12-23", respuesta.ultimosMovimientos().get(0).fecha());
    }

    @Test
    @DisplayName("El recorte se le pide al servicio core, no se hace después en el BFF")
    void elRecorteSePideAlCore() {
        servicio.obtenerResumenLiviano(CUENTA, 3);

        assertEquals(3, movimientos.ultimoLimitePedido,
                "el límite debe viajar al servicio core");
        assertEquals(0, movimientos.llamadasAHistorialCompleto,
                "no debe pedirse el historial completo para luego descartarlo");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, 999})
    @DisplayName("Un límite fuera de rango se rechaza antes de llamar a los servicios core")
    void limiteFueraDeRango(int limite) {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.obtenerResumenLiviano(CUENTA, limite));

        assertEquals(0, cuentas.llamadasAObtenerCuenta);
        assertEquals(0, movimientos.llamadasAUltimos);
    }

    @Test
    @DisplayName("El límite máximo aceptado es el declarado por el canal")
    void limiteMaximoAceptado() {
        assertDoesNotThrow(() -> servicio.obtenerResumenLiviano(CUENTA, CuentaMovilService.LIMITE_MAXIMO));
        assertEquals(CuentaMovilService.LIMITE_MAXIMO, movimientos.ultimoLimitePedido);
    }

    // --- Dobles de prueba -------------------------------------------------

    private static class CuentasFalso extends CuentasApiClient {

        CuentaDTO cuenta;
        int llamadasAObtenerCuenta;

        CuentasFalso() {
            super(null);
        }

        @Override
        public CuentaDTO obtenerCuenta(Long cuentaId) {
            llamadasAObtenerCuenta++;
            return cuenta;
        }
    }

    private static class MovimientosFalso extends MovimientosApiClient {

        List<MovimientoDTO> ultimos = List.of();
        int ultimoLimitePedido;
        int llamadasAUltimos;
        int llamadasAHistorialCompleto;

        MovimientosFalso() {
            super(null);
        }

        @Override
        public List<MovimientoDTO> obtenerUltimosMovimientos(Long cuentaId, int limite) {
            llamadasAUltimos++;
            ultimoLimitePedido = limite;
            return ultimos;
        }

        @Override
        public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
            llamadasAHistorialCompleto++;
            return ultimos;
        }
    }
}
