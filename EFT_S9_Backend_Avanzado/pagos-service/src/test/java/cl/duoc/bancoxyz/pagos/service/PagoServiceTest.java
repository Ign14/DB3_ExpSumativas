package cl.duoc.bancoxyz.pagos.service;

import cl.duoc.bancoxyz.common.dto.DepositoRequest;
import cl.duoc.bancoxyz.common.dto.OperacionResponse;
import cl.duoc.bancoxyz.common.dto.TransferenciaRequest;
import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import cl.duoc.bancoxyz.pagos.cliente.LiquidacionClienteResiliente;
import cl.duoc.bancoxyz.pagos.cliente.ResultadoLiquidacion;
import cl.duoc.bancoxyz.pagos.domain.MovimientoRepositoryEnMemoria;
import cl.duoc.bancoxyz.pagos.mensajeria.PublicadorEventosKafka;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de la orquestacion de pagos, con el foco puesto en lo que pasa cuando
 * una de las dos puntas de una transferencia falla.
 *
 * El caso interesante no es el feliz: es el que deja dinero descontado de una
 * cuenta sin abonar en la otra. De los nueve tests, cuatro son sobre eso, porque
 * es donde un sistema distribuido pierde plata de verdad y lo unico que lo
 * sostiene es que la compensacion funcione y que su fallo sea visible.
 *
 * Los dobles estan escritos a mano en vez de generados por una libreria de
 * mocks: asi los tests no dependen de instrumentacion de bytecode y corren igual
 * en cualquier version del JDK.
 */
class PagoServiceTest {

    private static final BigDecimal LIMITE = new BigDecimal("1000000");

    private LiquidacionFalsa liquidacion;
    private TopicosEspia topicos;
    private MovimientoRepositoryEnMemoria repositorio;
    private PagoService servicio;
    private Long cuentaConHistorial;

    @BeforeEach
    void preparar() {
        liquidacion = new LiquidacionFalsa();
        topicos = new TopicosEspia();
        repositorio = new MovimientoRepositoryEnMemoria();
        repositorio.cargarDatos();
        servicio = new PagoService(liquidacion, repositorio, topicos, LIMITE);
        cuentaConHistorial = 101L;
    }

    // --- Depositos ---------------------------------------------------------

    @Test
    @DisplayName("Un deposito abona la cuenta, deja el movimiento y publica la transaccion")
    void depositoAplicado() {
        liquidacion.saldoTrasAbonar = new BigDecimal("8500");

        OperacionResponse respuesta = servicio.depositar(cuentaConHistorial,
                new DepositoRequest(new BigDecimal("500"), "web", "Abono de sueldo"));

        assertTrue(respuesta.aprobada());
        assertEquals(TransaccionCompletadaEvento.DEPOSITO, respuesta.tipoOperacion());
        assertEquals(0, new BigDecimal("8500").compareTo(respuesta.saldoResultante()));
        assertEquals(1, liquidacion.abonos.size());
        assertEquals(1, topicos.transacciones.size());
        assertTrue(repositorio.obtenerMovimientos(cuentaConHistorial).stream()
                        .anyMatch(m -> "Abono de sueldo".equals(m.descripcion())),
                "el deposito deberia quedar en el historial con su descripcion");
    }

    @Test
    @DisplayName("Un deposito sobre el limite se rechaza sin tocar la cuenta")
    void depositoSobreLimite() {
        OperacionResponse respuesta = servicio.depositar(cuentaConHistorial,
                new DepositoRequest(LIMITE.add(BigDecimal.ONE), "web", null));

        assertFalse(respuesta.aprobada());
        assertTrue(respuesta.motivoRechazo().contains("limite"));
        assertTrue(liquidacion.abonos.isEmpty(), "no deberia llamarse al dominio de cuentas");
        assertTrue(topicos.transacciones.isEmpty());
    }

    @Test
    @DisplayName("Un monto cero, negativo o ausente se rechaza como peticion invalida")
    void montoInvalido() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.depositar(cuentaConHistorial, new DepositoRequest(BigDecimal.ZERO, "web", null)));
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.depositar(cuentaConHistorial,
                        new DepositoRequest(new BigDecimal("-10"), "web", null)));
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.depositar(cuentaConHistorial, new DepositoRequest(null, "web", null)));
        assertTrue(liquidacion.abonos.isEmpty());
    }

    // --- Transferencias ----------------------------------------------------

    @Test
    @DisplayName("Una transferencia carga el origen, abona el destino y deja dos movimientos")
    void transferenciaAplicada() {
        liquidacion.resultadoCargo = ResultadoLiquidacion.aplicada(new BigDecimal("6500"));
        liquidacion.saldoTrasAbonar = new BigDecimal("3000");

        OperacionResponse respuesta = servicio.transferir(101L,
                new TransferenciaRequest(102L, new BigDecimal("500"), "web", "Pago de arriendo"));

        assertTrue(respuesta.aprobada());
        assertEquals(TransaccionCompletadaEvento.TRANSFERENCIA, respuesta.tipoOperacion());
        assertEquals(List.of(101L), liquidacion.cargos, "el cargo va primero, al origen");
        assertEquals(List.of(102L), liquidacion.abonos, "y solo despues el abono al destino");
        assertEquals(1, topicos.transacciones.size());
        assertEquals(102L, topicos.transacciones.get(0).cuentaDestino());
    }

    @Test
    @DisplayName("El cargo precede al abono: nunca se crea dinero antes de descontarlo")
    void elOrdenEsCargoYLuegoAbono() {
        liquidacion.resultadoCargo = ResultadoLiquidacion.aplicada(new BigDecimal("6500"));
        liquidacion.saldoTrasAbonar = new BigDecimal("3000");

        servicio.transferir(101L, new TransferenciaRequest(102L, new BigDecimal("500"), "web", null));

        assertEquals(List.of("cargo:101", "abono:102"), liquidacion.secuencia);
    }

    @Test
    @DisplayName("Sin fondos en el origen no se abona el destino ni se publica nada")
    void transferenciaSinFondos() {
        liquidacion.resultadoCargo = ResultadoLiquidacion.rechazada("Fondos insuficientes");

        OperacionResponse respuesta = servicio.transferir(101L,
                new TransferenciaRequest(102L, new BigDecimal("500"), "web", null));

        assertFalse(respuesta.aprobada());
        assertEquals("Fondos insuficientes", respuesta.motivoRechazo());
        assertTrue(liquidacion.abonos.isEmpty(), "no hay que abonar lo que no se pudo cargar");
        assertTrue(topicos.transacciones.isEmpty());
    }

    @Test
    @DisplayName("Transferir a la misma cuenta se rechaza como peticion invalida")
    void transferenciaASiMismo() {
        assertThrows(ParametroInvalidoException.class, () -> servicio.transferir(101L,
                new TransferenciaRequest(101L, new BigDecimal("500"), "web", null)));
        assertTrue(liquidacion.cargos.isEmpty());
    }

    @Test
    @DisplayName("Sin cuenta de destino se rechaza antes de tocar el origen")
    void transferenciaSinDestino() {
        assertThrows(ParametroInvalidoException.class, () -> servicio.transferir(101L,
                new TransferenciaRequest(null, new BigDecimal("500"), "web", null)));
        assertTrue(liquidacion.cargos.isEmpty());
    }

    // --- Compensacion ------------------------------------------------------

    @Test
    @DisplayName("Si el abono al destino falla, se devuelve el monto al origen")
    void abonoFallidoSeCompensa() {
        liquidacion.resultadoCargo = ResultadoLiquidacion.aplicada(new BigDecimal("6500"));
        liquidacion.fallarPrimerAbono = new ServicioNoDisponibleException("cuentas-service no responde");
        liquidacion.saldoTrasAbonar = new BigDecimal("7000");

        assertThrows(ServicioNoDisponibleException.class, () -> servicio.transferir(101L,
                new TransferenciaRequest(102L, new BigDecimal("500"), "web", null)));

        assertEquals(List.of("cargo:101", "abono:102", "abono:101"), liquidacion.secuencia,
                "tras fallar el abono al destino, el monto vuelve al origen");
        assertTrue(topicos.alertas.isEmpty(), "una compensacion exitosa no es una alerta");
        assertTrue(topicos.transacciones.isEmpty(), "la transferencia no ocurrio: no hay hecho que publicar");
    }

    @Test
    @DisplayName("La compensacion exitosa queda como reverso en el historial del origen")
    void compensacionDejaReversoEnElHistorial() {
        liquidacion.resultadoCargo = ResultadoLiquidacion.aplicada(new BigDecimal("6500"));
        liquidacion.fallarPrimerAbono = new ServicioNoDisponibleException("cuentas-service no responde");
        liquidacion.saldoTrasAbonar = new BigDecimal("7000");

        assertThrows(ServicioNoDisponibleException.class, () -> servicio.transferir(101L,
                new TransferenciaRequest(102L, new BigDecimal("500"), "web", null)));

        assertTrue(repositorio.obtenerMovimientos(101L).stream()
                        .anyMatch(m -> m.descripcion() != null && m.descripcion().startsWith("Reverso")),
                "la compensacion es una operacion de negocio y deja rastro, no es un rollback invisible");
    }

    @Test
    @DisplayName("Si la compensacion tambien falla, se publica una alerta de severidad alta")
    void compensacionFallidaGeneraAlertaAlta() {
        liquidacion.resultadoCargo = ResultadoLiquidacion.aplicada(new BigDecimal("6500"));
        liquidacion.fallarTodosLosAbonos = new ServicioNoDisponibleException("cuentas-service no responde");

        assertThrows(ServicioNoDisponibleException.class, () -> servicio.transferir(101L,
                new TransferenciaRequest(102L, new BigDecimal("500"), "web", null)));

        assertEquals(1, topicos.alertas.size());
        AlertaSeguridadEvento alerta = topicos.alertas.get(0);
        assertEquals(AlertaSeguridadEvento.COMPENSACION_FALLIDA, alerta.tipoAlerta());
        assertEquals(AlertaSeguridadEvento.SEVERIDAD_ALTA, alerta.severidad());
        assertEquals(101L, alerta.cuentaId(), "la alerta apunta a la cuenta que quedo descontada");
        assertTrue(alerta.detalle().contains("102"),
                "el detalle tiene que nombrar el destino para poder reponerlo a mano");
    }

    @Test
    @DisplayName("Una cuenta de destino inexistente tambien se compensa")
    void destinoInexistenteSeCompensa() {
        liquidacion.resultadoCargo = ResultadoLiquidacion.aplicada(new BigDecimal("6500"));
        liquidacion.fallarPrimerAbono = new RecursoNoEncontradoException("La cuenta 999 no existe.");
        liquidacion.saldoTrasAbonar = new BigDecimal("7000");

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.transferir(101L,
                new TransferenciaRequest(999L, new BigDecimal("500"), "web", null)));

        assertEquals(List.of("cargo:101", "abono:999", "abono:101"), liquidacion.secuencia);
    }

    // --- Dobles de prueba --------------------------------------------------

    /**
     * Cliente de liquidacion controlable. Registra la secuencia exacta de
     * llamadas, que es lo que permite afirmar que el cargo precede al abono y
     * que la compensacion ocurre despues del fallo.
     */
    private static class LiquidacionFalsa extends LiquidacionClienteResiliente {

        final List<Long> cargos = new ArrayList<>();
        final List<Long> abonos = new ArrayList<>();
        final List<String> secuencia = new ArrayList<>();

        ResultadoLiquidacion resultadoCargo = ResultadoLiquidacion.aplicada(BigDecimal.ZERO);
        BigDecimal saldoTrasAbonar = BigDecimal.ZERO;
        RuntimeException fallarPrimerAbono;
        RuntimeException fallarTodosLosAbonos;
        private int abonosIntentados;

        LiquidacionFalsa() {
            super(null);
        }

        @Override
        public ResultadoLiquidacion cargar(Long cuentaId, BigDecimal monto) {
            cargos.add(cuentaId);
            secuencia.add("cargo:" + cuentaId);
            return resultadoCargo;
        }

        @Override
        public BigDecimal abonar(Long cuentaId, BigDecimal monto) {
            abonosIntentados++;
            secuencia.add("abono:" + cuentaId);
            if (fallarTodosLosAbonos != null) {
                throw fallarTodosLosAbonos;
            }
            if (fallarPrimerAbono != null && abonosIntentados == 1) {
                throw fallarPrimerAbono;
            }
            abonos.add(cuentaId);
            return saldoTrasAbonar;
        }
    }

    private static class TopicosEspia extends PublicadorEventosKafka {

        final List<TransaccionCompletadaEvento> transacciones = new ArrayList<>();
        final List<AlertaSeguridadEvento> alertas = new ArrayList<>();

        TopicosEspia() {
            super(null);
        }

        @Override
        public boolean publicarTransaccion(TransaccionCompletadaEvento evento) {
            transacciones.add(evento);
            return true;
        }

        @Override
        public void publicarAlerta(AlertaSeguridadEvento evento) {
            alertas.add(evento);
        }
    }
}
