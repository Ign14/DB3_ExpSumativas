package cl.duoc.bancoxyz.bff.web.service;

import cl.duoc.bancoxyz.bff.common.client.ClientesApiClient;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.client.PagosApiClient;
import cl.duoc.bancoxyz.common.dto.ClienteDTO;
import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.dto.OperacionResponse;
import cl.duoc.bancoxyz.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import cl.duoc.bancoxyz.bff.web.dto.CuentaWebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de la agregacion del canal web.
 *
 * El foco esta en la degradacion parcial, que es la decision de diseno propia
 * de este canal: el perfil del titular es informacion de contexto, no el dato
 * que el operador vino a buscar, asi que una caida de clientes-service tiene
 * que dejar la pantalla funcionando en vez de tumbarla. Es la misma idea que el
 * fallback de pagos-service, aplicada una capa mas arriba, y hasta ahora no
 * tenia ninguna prueba.
 *
 * Los dobles estan escritos a mano, como en el resto del proyecto: las
 * librerias de mocks instrumentan bytecode y se rompen al cambiar de version
 * del JDK.
 */
class CuentaWebServiceTest {

    private static final Long CUENTA = 101L;

    private CuentasFalso cuentas;
    private MovimientosFalso movimientos;
    private ClientesFalso clientes;
    private PagosFalso pagos;
    private CuentaWebService servicio;

    @BeforeEach
    void preparar() {
        cuentas = new CuentasFalso();
        movimientos = new MovimientosFalso();
        clientes = new ClientesFalso();
        pagos = new PagosFalso();
        servicio = new CuentaWebService(cuentas, movimientos, clientes, pagos);

        cuentas.cuenta = new CuentaDTO(CUENTA, "John Doe", 40, "prestamo", new BigDecimal("5000"));
        movimientos.historial = List.of(
                new MovimientoDTO(CUENTA, "2026-10-01", "deposito", new BigDecimal("300"), "Abono"),
                new MovimientoDTO(CUENTA, "2026-10-02", "retiro", new BigDecimal("100"), "Cajero"));
        movimientos.resumen = new ResumenMovimientosDTO(
                CUENTA, 2, new BigDecimal("300"), new BigDecimal("100"), "2026-10-02");
        clientes.perfil = new ClienteDTO(CUENTA, "John Doe", "establecido", 40,
                "prestamo", new BigDecimal("5000"), 2, 0, "RETIRO de 100");
    }

    @Test
    @DisplayName("Una peticion del canal se traduce en cuatro llamadas a tres microservicios")
    void agregaCuatroLlamadas() {
        // Es el trabajo que el patron BFF le quita al frontend: sin el, el
        // navegador tendria que hacer las cuatro, conocer la forma de las tres
        // APIs y manejar por su cuenta que una falle.
        CuentaWebResponse respuesta = servicio.obtenerCuentaCompleta(CUENTA);

        assertEquals(1, cuentas.llamadas);
        assertEquals(1, movimientos.llamadasHistorial);
        assertEquals(1, movimientos.llamadasResumen);
        assertEquals(1, clientes.llamadas);

        assertNotNull(respuesta.cuenta());
        assertNotNull(respuesta.perfil());
        assertEquals(2, respuesta.historialCompleto().size());
        assertEquals(2, respuesta.resumen().totalMovimientos());
    }

    @Test
    @DisplayName("Si clientes-service no responde, la cuenta y el historial se entregan igual")
    void degradaSinPerfilCuandoClientesNoResponde() {
        // La prueba central de este archivo. El operador necesita ver el saldo y
        // el historial; el perfil comercial es un adorno. Si esta llamada
        // propagara la excepcion, una caida de clientes-service dejaria la
        // pantalla completa en blanco por un dato secundario.
        clientes.falla = new ServicioCoreNoDisponibleException("clientes-service");

        CuentaWebResponse respuesta = servicio.obtenerCuentaCompleta(CUENTA);

        assertNull(respuesta.perfil(), "el bloque no disponible viaja en nulo, no revienta la respuesta");
        assertNotNull(respuesta.cuenta());
        assertEquals(2, respuesta.historialCompleto().size());
        assertNotNull(respuesta.resumen());
    }

    @Test
    @DisplayName("Una cuenta sin titular en el padron tampoco es un error")
    void degradaSinPerfilCuandoElClienteNoExiste() {
        // El padron de clientes y el de cuentas salen del mismo archivo legacy
        // pero con reglas de validacion distintas, asi que hay cuentas validas
        // cuyo titular quedo fuera por edad o por tipo de producto.
        clientes.falla = new CuentaNoEncontradaException(CUENTA);

        CuentaWebResponse respuesta = servicio.obtenerCuentaCompleta(CUENTA);

        assertNull(respuesta.perfil());
        assertNotNull(respuesta.cuenta());
    }

    @Test
    @DisplayName("Si cuentas-service no responde, la peticion SI falla")
    void noDegradaSiFaltaElDatoPrincipal() {
        // El limite de la degradacion. Una respuesta sin saldo no es una
        // respuesta degradada util: es una pantalla vacia con apariencia de
        // exito, y el canal no debe fabricarla.
        cuentas.falla = new ServicioCoreNoDisponibleException("cuentas-service");

        assertThrows(ServicioCoreNoDisponibleException.class,
                () -> servicio.obtenerCuentaCompleta(CUENTA));
    }

    @Test
    @DisplayName("Si falla el historial, la peticion tambien falla")
    void noDegradaSiFaltaElHistorial() {
        movimientos.falla = new ServicioCoreNoDisponibleException("pagos-service");

        assertThrows(ServicioCoreNoDisponibleException.class,
                () -> servicio.obtenerCuentaCompleta(CUENTA));
    }

    @Test
    @DisplayName("El canal se identifica como 'web' en las operaciones que mueven dinero")
    void elCanalViajaEnLasOperaciones() {
        // El canal viaja hasta el evento que se publica, y eso permite despues
        // responder cuanto se mueve por cada canal.
        servicio.depositar(CUENTA, new BigDecimal("500"), "Abono por caja");
        servicio.transferir(CUENTA, 102L, new BigDecimal("300"), "Arriendo");

        assertEquals("web", pagos.ultimoCanalDeposito);
        assertEquals("web", pagos.ultimoCanalTransferencia);
        assertEquals(102L, pagos.ultimoDestino);
    }

    @Test
    @DisplayName("La edicion del nombre llega al dominio de clientes tal como la recibio")
    void actualizarNombre() {
        servicio.actualizarNombreTitular(CUENTA, "Titular Corregido");

        assertEquals("Titular Corregido", clientes.ultimoNombre);
        assertEquals(CUENTA, clientes.ultimoClienteRenombrado);
    }

    @Test
    @DisplayName("Una caida de clientes-service al editar SI se propaga")
    void escribirNoSeDegrada() {
        // Leer el perfil se degrada; escribirlo no. Si la edicion fallara en
        // silencio, el operador creeria haber corregido un dato personal que
        // sigue como estaba.
        clientes.falla = new ServicioCoreNoDisponibleException("clientes-service");

        assertThrows(ServicioCoreNoDisponibleException.class,
                () -> servicio.actualizarNombreTitular(CUENTA, "Titular Corregido"));
    }

    // --- Dobles de prueba --------------------------------------------------

    private static class CuentasFalso extends CuentasApiClient {
        CuentaDTO cuenta;
        RuntimeException falla;
        int llamadas;

        CuentasFalso() {
            super(null);
        }

        @Override
        public CuentaDTO obtenerCuenta(Long cuentaId) {
            llamadas++;
            if (falla != null) {
                throw falla;
            }
            return cuenta;
        }

        @Override
        public List<CuentaDTO> listarCuentas() {
            return cuenta == null ? List.of() : List.of(cuenta);
        }
    }

    private static class MovimientosFalso extends MovimientosApiClient {
        List<MovimientoDTO> historial = new ArrayList<>();
        ResumenMovimientosDTO resumen;
        RuntimeException falla;
        int llamadasHistorial;
        int llamadasResumen;

        MovimientosFalso() {
            super(null);
        }

        @Override
        public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
            llamadasHistorial++;
            if (falla != null) {
                throw falla;
            }
            return historial;
        }

        @Override
        public ResumenMovimientosDTO obtenerResumen(Long cuentaId) {
            llamadasResumen++;
            if (falla != null) {
                throw falla;
            }
            return resumen;
        }
    }

    private static class ClientesFalso extends ClientesApiClient {
        ClienteDTO perfil;
        RuntimeException falla;
        int llamadas;
        String ultimoNombre;
        Long ultimoClienteRenombrado;

        ClientesFalso() {
            super(null);
        }

        @Override
        public ClienteDTO obtenerPerfil(Long clienteId) {
            llamadas++;
            if (falla != null) {
                throw falla;
            }
            return perfil;
        }

        @Override
        public ClienteDTO actualizarNombre(Long clienteId, String nombre) {
            if (falla != null) {
                throw falla;
            }
            ultimoClienteRenombrado = clienteId;
            ultimoNombre = nombre;
            return perfil;
        }
    }

    private static class PagosFalso extends PagosApiClient {
        String ultimoCanalDeposito;
        String ultimoCanalTransferencia;
        Long ultimoDestino;

        PagosFalso() {
            super(null);
        }

        @Override
        public OperacionResponse depositar(Long cuentaId, BigDecimal monto,
                                               String canal, String descripcion) {
            ultimoCanalDeposito = canal;
            return new OperacionResponse("op-1", "DEPOSITO", cuentaId, cuentaId,
                    monto, monto, true, null, true);
        }

        @Override
        public OperacionResponse transferir(Long cuentaOrigen, Long cuentaDestino, BigDecimal monto,
                                                String canal, String descripcion) {
            ultimoCanalTransferencia = canal;
            ultimoDestino = cuentaDestino;
            return new OperacionResponse("op-2", "TRANSFERENCIA", cuentaOrigen, cuentaDestino,
                    monto, monto, true, null, true);
        }
    }
}
