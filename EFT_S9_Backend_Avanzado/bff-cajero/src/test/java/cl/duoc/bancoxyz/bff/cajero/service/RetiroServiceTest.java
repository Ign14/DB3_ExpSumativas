package cl.duoc.bancoxyz.bff.cajero.service;

import cl.duoc.bancoxyz.bff.cajero.dto.ComprobanteRetiro;
import cl.duoc.bancoxyz.bff.cajero.dto.SaldoCajeroResponse;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cliente hacia el dominio de cuentas se reemplaza por un doble escrito a
 * mano en vez de una libreria de mocks: asi los tests no dependen de
 * instrumentacion de bytecode y corren igual en cualquier version del JDK.
 *
 * Varios de estos tests comprueban que el canal NO llama al microservicio. Eso
 * es lo que justifica que el limite viva aqui: si el canal llamara igual y
 * dejara que el dominio rechazara, el limite del canal no estaria ahorrando
 * nada y seria solo un numero duplicado en dos lugares.
 */
class RetiroServiceTest {

    private static final Long CUENTA = 103L;
    private static final BigDecimal LIMITE = new BigDecimal("500000");

    private CuentasFalso cuentas;
    private RetiroService servicio;

    @BeforeEach
    void setUp() {
        cuentas = new CuentasFalso();
        servicio = new RetiroService(cuentas, LIMITE);
    }

    @Test
    @DisplayName("Un retiro aprobado es una sola llamada y el canal informa que el evento salio")
    void retiroAprobadoEsUnaSolaLlamada() {
        BigDecimal monto = new BigDecimal("500");
        cuentas.respuesta = new RetiroResponse(
                CUENTA, monto, new BigDecimal("6500"), true, null, "evt-1", true);

        ComprobanteRetiro respuesta = servicio.retirar(CUENTA, monto);

        assertTrue(respuesta.aprobado());
        assertTrue(respuesta.movimientoRegistrado(),
                "con el evento publicado, el movimiento llegara al historial por la cola");
        assertEquals(0, new BigDecimal("6500").compareTo(respuesta.saldoResultante()));
        assertEquals(1, cuentas.llamadasARetirar, "el canal ya no orquesta dos servicios");
        assertEquals(0, monto.compareTo(cuentas.ultimoMonto));
    }

    @Test
    @DisplayName("El canal se identifica en la llamada, para que el evento sepa de donde vino")
    void elCanalViajaEnLaLlamada() {
        cuentas.respuesta = new RetiroResponse(
                CUENTA, new BigDecimal("500"), new BigDecimal("6500"), true, null, "evt-2", true);

        servicio.retirar(CUENTA, new BigDecimal("500"));

        assertEquals("cajero", cuentas.ultimoCanal);
    }

    @Test
    @DisplayName("Si el evento no se publico, el retiro sigue aprobado y el comprobante lo advierte")
    void retiroAprobadoConEventoNoPublicado() {
        BigDecimal monto = new BigDecimal("500");
        cuentas.respuesta = new RetiroResponse(
                CUENTA, monto, new BigDecimal("6500"), true, null, "evt-3", false);

        ComprobanteRetiro respuesta = servicio.retirar(CUENTA, monto);

        assertTrue(respuesta.aprobado(), "el dinero ya salio de la cuenta: el retiro no se revierte");
        assertFalse(respuesta.movimientoRegistrado(),
                "sin evento, el movimiento no va a aparecer en el historial");
        assertEquals(0, new BigDecimal("6500").compareTo(respuesta.saldoResultante()));
    }

    @Test
    @DisplayName("Un monto sobre el limite del canal se rechaza sin llegar al microservicio")
    void montoSobreLimiteNoLlegaAlMicroservicio() {
        ComprobanteRetiro respuesta = servicio.retirar(CUENTA, new BigDecimal("600000"));

        assertFalse(respuesta.aprobado());
        assertTrue(respuesta.motivoRechazo().contains("límite máximo por operación"));
        assertEquals(0, cuentas.llamadasARetirar);
    }

    @Test
    @DisplayName("Sin fondos suficientes se rechaza con el motivo que dio el dominio")
    void sinFondosSeRechazaConElMotivoDelDominio() {
        BigDecimal monto = new BigDecimal("400000");
        cuentas.respuesta = new RetiroResponse(
                CUENTA, monto, new BigDecimal("6500"), false, "Fondos insuficientes", null, false);

        ComprobanteRetiro respuesta = servicio.retirar(CUENTA, monto);

        assertFalse(respuesta.aprobado());
        assertEquals("Fondos insuficientes", respuesta.motivoRechazo());
        assertEquals(1, cuentas.llamadasARetirar);
        assertNull(respuesta.fechaMovimiento(), "un rechazo no deja fecha de movimiento");
    }

    @Test
    @DisplayName("Un monto cero, negativo o ausente se rechaza sin llegar al microservicio")
    void montoInvalido() {
        assertFalse(servicio.retirar(CUENTA, BigDecimal.ZERO).aprobado());
        assertFalse(servicio.retirar(CUENTA, new BigDecimal("-100")).aprobado());
        assertFalse(servicio.retirar(CUENTA, null).aprobado());

        assertEquals(0, cuentas.llamadasARetirar);
    }

    @Test
    @DisplayName("La consulta de saldo no expone datos del titular")
    void consultaDeSaldoSoloDevuelveSaldo() {
        cuentas.cuenta = new CuentaDTO(CUENTA, "Bob Johnson", 30, "ahorro", new BigDecimal("7000"));

        SaldoCajeroResponse saldo = servicio.consultarSaldo(CUENTA);

        assertEquals(CUENTA, saldo.cuentaId());
        assertEquals(0, new BigDecimal("7000").compareTo(saldo.saldoDisponible()));
    }

    // --- Doble de prueba --------------------------------------------------

    private static class CuentasFalso extends CuentasApiClient {

        CuentaDTO cuenta;
        RetiroResponse respuesta;
        int llamadasARetirar;
        BigDecimal ultimoMonto;
        String ultimoCanal;

        CuentasFalso() {
            super(null);
        }

        @Override
        public CuentaDTO obtenerCuenta(Long cuentaId) {
            return cuenta;
        }

        @Override
        public RetiroResponse retirar(Long cuentaId, BigDecimal monto, String canal) {
            llamadasARetirar++;
            ultimoMonto = monto;
            ultimoCanal = canal;
            return respuesta;
        }
    }
}
