package cl.duoc.bancoxyz.cuentas.service;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.cuentas.domain.CuentaRepositoryEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las primitivas de liquidacion son las que usa pagos-service para mover las dos
 * puntas de una transferencia. Lo que estas pruebas fijan es que no publican
 * eventos, que un cargo sin fondos se informa en vez de lanzar, y que sumar y
 * restar saldo sigue siendo atomico bajo concurrencia.
 */
class LiquidacionServiceTest {

    private CuentaRepositoryEnMemoria repositorio;
    private LiquidacionService servicio;
    private Long cuenta;
    private BigDecimal saldoInicial;

    @BeforeEach
    void preparar() {
        repositorio = new CuentaRepositoryEnMemoria();
        repositorio.cargarDatos();
        servicio = new LiquidacionService(repositorio);
        CuentaDTO alguna = repositorio.listar().stream()
                .filter(c -> c.saldo().compareTo(new BigDecimal("1000")) >= 0)
                .findFirst().orElseThrow();
        cuenta = alguna.cuentaId();
        saldoInicial = alguna.saldo();
    }

    @Test
    @DisplayName("Un abono suma al saldo y devuelve el resultante")
    void abonoSumaAlSaldo() {
        BigDecimal saldo = servicio.abonar(cuenta, new BigDecimal("500"));

        assertEquals(0, saldoInicial.add(new BigDecimal("500")).compareTo(saldo));
        assertEquals(0, saldo.compareTo(repositorio.buscar(cuenta).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("Un cargo con fondos resta del saldo")
    void cargoRestaDelSaldo() {
        Optional<BigDecimal> saldo = servicio.cargar(cuenta, new BigDecimal("500"));

        assertTrue(saldo.isPresent());
        assertEquals(0, saldoInicial.subtract(new BigDecimal("500")).compareTo(saldo.get()));
    }

    @Test
    @DisplayName("Un cargo sin fondos devuelve vacio en vez de lanzar, y no toca el saldo")
    void cargoSinFondosDevuelveVacio() {
        // Para quien orquesta una transferencia, "no habia saldo" es una
        // respuesta esperada que tiene que poder manejar. Si esto lanzara, el
        // cliente resiliente lo contaria como un fallo de la dependencia y
        // acabaria abriendo el circuito por rechazos de negocio.
        Optional<BigDecimal> saldo = servicio.cargar(cuenta, saldoInicial.add(BigDecimal.ONE));

        assertTrue(saldo.isEmpty());
        assertEquals(0, saldoInicial.compareTo(repositorio.buscar(cuenta).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("Liquidar sobre una cuenta inexistente es un error, no un resultado")
    void cuentaInexistente() {
        // Consultar una cuenta que no existe es una pregunta legitima. Mover
        // dinero sobre una cuenta que no existe significa que el sistema intento
        // moverlo a ninguna parte, y eso tiene que llegar a quien orquesta.
        assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.abonar(999999L, new BigDecimal("100")));
        assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.cargar(999999L, new BigDecimal("100")));
    }

    @Test
    @DisplayName("Un monto cero, negativo o ausente se rechaza en las dos primitivas")
    void montoInvalido() {
        for (BigDecimal monto : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("-100"), null}) {
            assertThrows(ParametroInvalidoException.class, () -> servicio.abonar(cuenta, monto));
            assertThrows(ParametroInvalidoException.class, () -> servicio.cargar(cuenta, monto));
        }
        assertEquals(0, saldoInicial.compareTo(repositorio.buscar(cuenta).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("Veinte hilos abonando a la vez no pierden ningun abono")
    void abonoAtomicoBajoConcurrencia() throws Exception {
        int hilos = 20;
        BigDecimal monto = new BigDecimal("10");
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch partida = new CountDownLatch(1);
        CountDownLatch llegada = new CountDownLatch(hilos);

        for (int i = 0; i < hilos; i++) {
            pool.submit(() -> {
                try {
                    partida.await();
                    servicio.abonar(cuenta, monto);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    llegada.countDown();
                }
            });
        }
        partida.countDown();
        assertTrue(llegada.await(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        BigDecimal esperado = saldoInicial.add(monto.multiply(new BigDecimal(hilos)));
        assertEquals(0, esperado.compareTo(repositorio.buscar(cuenta).orElseThrow().saldo()),
                "el registro es inmutable y cada abono lo reemplaza: sin compute se perderian abonos");
    }
}
