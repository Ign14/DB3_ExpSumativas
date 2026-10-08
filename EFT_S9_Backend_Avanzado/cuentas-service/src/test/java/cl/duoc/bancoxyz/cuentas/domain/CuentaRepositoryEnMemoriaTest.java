package cl.duoc.bancoxyz.cuentas.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuentaRepositoryEnMemoriaTest {

    private CuentaRepositoryEnMemoria repositorio;

    @BeforeEach
    void cargar() {
        repositorio = new CuentaRepositoryEnMemoria();
        repositorio.cargarDatos();
    }

    @Test
    @DisplayName("Carga el dataset legacy y deja fuera las filas inconsistentes")
    void cargaYFiltra() {
        List<cl.duoc.bancoxyz.common.dto.CuentaDTO> cuentas = repositorio.listar();
        assertFalse(cuentas.isEmpty(), "el CSV del classpath deberia producir cuentas");
        cuentas.forEach(cuenta -> {
            assertTrue(cuenta.saldo().signum() >= 0, "ninguna cuenta cargada puede tener saldo negativo");
            assertTrue(cuenta.edad() >= 18 && cuenta.edad() <= 120, "edad fuera del rango valido: " + cuenta.edad());
            assertTrue(List.of("ahorro", "prestamo", "hipoteca").contains(cuenta.tipoCuenta()),
                    "tipo fuera del dominio: " + cuenta.tipoCuenta());
        });
    }

    @Test
    @DisplayName("El listado viene ordenado por identificador de cuenta")
    void listadoOrdenado() {
        List<Long> ids = repositorio.listar().stream().map(c -> c.cuentaId()).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    @DisplayName("Una cuenta inexistente no se encuentra y no se puede debitar")
    void cuentaInexistente() {
        assertTrue(repositorio.buscar(999_999L).isEmpty());
        assertTrue(repositorio.debitar(999_999L, BigDecimal.TEN).isEmpty());
    }

    @Test
    @DisplayName("El debito baja el saldo exactamente en el monto retirado")
    void debitoAplicaMonto() {
        Long id = repositorio.listar().get(0).cuentaId();
        BigDecimal saldoInicial = repositorio.buscar(id).orElseThrow().saldo();

        Optional<CuentaRepositoryEnMemoria.ResultadoDebito> resultado =
                repositorio.debitar(id, new BigDecimal("100"));

        assertTrue(resultado.orElseThrow().aprobado());
        assertEquals(0, saldoInicial.subtract(new BigDecimal("100"))
                .compareTo(repositorio.buscar(id).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("Un retiro mayor que el saldo se rechaza y no modifica la cuenta")
    void rechazaPorFondos() {
        Long id = repositorio.listar().get(0).cuentaId();
        BigDecimal saldoInicial = repositorio.buscar(id).orElseThrow().saldo();

        CuentaRepositoryEnMemoria.ResultadoDebito resultado =
                repositorio.debitar(id, saldoInicial.add(BigDecimal.ONE)).orElseThrow();

        assertFalse(resultado.aprobado());
        assertEquals("Fondos insuficientes", resultado.motivoRechazo());
        assertEquals(0, saldoInicial.compareTo(repositorio.buscar(id).orElseThrow().saldo()));
    }

    /**
     * Un monto negativo en un {@code subtract} aumenta el saldo. La invariante
     * tiene que estar dentro del repositorio y no solo en la capa de servicio: si
     * viviera afuera, el segundo llamador que apareciera podria crear dinero.
     */
    @Test
    @DisplayName("El repositorio rechaza montos no positivos: la invariante no vive en el servicio")
    void rechazaMontosNoPositivos() {
        Long id = repositorio.listar().get(0).cuentaId();
        BigDecimal saldoInicial = repositorio.buscar(id).orElseThrow().saldo();

        assertThrows(IllegalArgumentException.class, () -> repositorio.debitar(id, new BigDecimal("-100")));
        assertThrows(IllegalArgumentException.class, () -> repositorio.debitar(id, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> repositorio.debitar(id, null));

        assertEquals(0, saldoInicial.compareTo(repositorio.buscar(id).orElseThrow().saldo()),
                "ningun intento invalido debio tocar el saldo");
    }

    /**
     * Este es el test que justifica usar {@code compute} en el repositorio. Con
     * una lectura y una escritura separadas, veinte hilos que retiran a la vez
     * parten varios del mismo saldo y el banco regala dinero.
     */
    @Test
    @DisplayName("Veinte retiros simultaneos descuentan los veinte montos")
    void debitosConcurrentesNoSePierden() throws Exception {
        Long id = repositorio.listar().stream()
                .filter(c -> c.saldo().compareTo(new BigDecimal("2000")) >= 0)
                .findFirst().orElseThrow().cuentaId();
        BigDecimal saldoInicial = repositorio.buscar(id).orElseThrow().saldo();

        int hilos = 20;
        BigDecimal monto = new BigDecimal("100");
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch partida = new CountDownLatch(1);
        CountDownLatch llegada = new CountDownLatch(hilos);
        for (int i = 0; i < hilos; i++) {
            pool.submit(() -> {
                try {
                    partida.await();
                    repositorio.debitar(id, monto);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    llegada.countDown();
                }
            });
        }
        partida.countDown();
        assertTrue(llegada.await(10, TimeUnit.SECONDS), "los retiros no terminaron a tiempo");
        pool.shutdownNow();

        BigDecimal esperado = saldoInicial.subtract(monto.multiply(BigDecimal.valueOf(hilos)));
        assertEquals(0, esperado.compareTo(repositorio.buscar(id).orElseThrow().saldo()),
                "se perdio al menos un debito: el saldo no bajo lo que debia");
    }
}
