package cl.duoc.bancoxyz.bff.cuentas.domain;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CuentaRepositoryEnMemoriaTest {

    private CuentaRepositoryEnMemoria repositorio;

    @BeforeEach
    void setUp() {
        repositorio = new CuentaRepositoryEnMemoria();
        repositorio.cargarDatos();
    }

    @Test
    @DisplayName("Carga solo las cuentas que cumplen las reglas de validación")
    void cargaSoloCuentasValidas() {
        List<CuentaDTO> cuentas = repositorio.listar();

        assertFalse(cuentas.isEmpty(), "debe cargar al menos una cuenta valida");
        assertTrue(cuentas.stream().allMatch(c -> c.saldo().signum() >= 0),
                "ninguna cuenta cargada puede tener saldo negativo");
        assertTrue(cuentas.stream().allMatch(c -> c.edad() >= 18 && c.edad() <= 120),
                "ninguna cuenta cargada puede tener edad fuera de [18,120]");
        assertTrue(cuentas.stream().allMatch(c -> List.of("ahorro", "prestamo", "hipoteca").contains(c.tipoCuenta())),
                "ninguna cuenta cargada puede tener un tipo fuera de dominio");
    }

    @Test
    @DisplayName("Un débito con fondos suficientes descuenta exactamente el monto")
    void debitoConFondosSuficientes() {
        CuentaDTO cuenta = repositorio.listar().get(0);
        BigDecimal saldoInicial = cuenta.saldo();
        BigDecimal monto = new BigDecimal("100");

        Optional<CuentaRepositoryEnMemoria.ResultadoDebito> resultado =
                repositorio.debitar(cuenta.cuentaId(), monto);

        assertTrue(resultado.isPresent());
        assertTrue(resultado.get().aprobado());
        assertEquals(0, saldoInicial.subtract(monto).compareTo(resultado.get().saldoResultante()));
        assertEquals(0, saldoInicial.subtract(monto).compareTo(
                repositorio.buscar(cuenta.cuentaId()).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("Un débito sin fondos suficientes se rechaza y deja el saldo intacto")
    void debitoSinFondosSuficientes() {
        CuentaDTO cuenta = repositorio.listar().get(0);
        BigDecimal saldoInicial = cuenta.saldo();

        Optional<CuentaRepositoryEnMemoria.ResultadoDebito> resultado =
                repositorio.debitar(cuenta.cuentaId(), saldoInicial.add(BigDecimal.ONE));

        assertTrue(resultado.isPresent());
        assertFalse(resultado.get().aprobado());
        assertEquals("Fondos insuficientes", resultado.get().motivoRechazo());
        assertEquals(0, saldoInicial.compareTo(repositorio.buscar(cuenta.cuentaId()).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("Debitar una cuenta inexistente devuelve vacío y no crea la cuenta")
    void debitoSobreCuentaInexistente() {
        assertTrue(repositorio.debitar(999_999L, new BigDecimal("100")).isEmpty());
        assertTrue(repositorio.buscar(999_999L).isEmpty());
    }

    @Test
    @DisplayName("Retiros concurrentes no se pisan entre sí: el saldo final refleja todos los débitos aprobados")
    void debitosConcurrentesNoPierdenActualizaciones() throws InterruptedException {
        CuentaDTO cuenta = repositorio.listar().stream()
                .max((a, b) -> a.saldo().compareTo(b.saldo()))
                .orElseThrow();
        BigDecimal saldoInicial = cuenta.saldo();

        int hilos = 20;
        BigDecimal montoPorRetiro = new BigDecimal("100");
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch partidaSimultanea = new CountDownLatch(1);
        CountDownLatch terminados = new CountDownLatch(hilos);
        AtomicInteger aprobados = new AtomicInteger();

        for (int i = 0; i < hilos; i++) {
            pool.submit(() -> {
                try {
                    partidaSimultanea.await();
                    repositorio.debitar(cuenta.cuentaId(), montoPorRetiro)
                            .filter(CuentaRepositoryEnMemoria.ResultadoDebito::aprobado)
                            .ifPresent(r -> aprobados.incrementAndGet());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    terminados.countDown();
                }
            });
        }

        partidaSimultanea.countDown();
        assertTrue(terminados.await(10, TimeUnit.SECONDS), "los retiros deben terminar antes del timeout");
        pool.shutdown();

        BigDecimal esperado = saldoInicial.subtract(montoPorRetiro.multiply(new BigDecimal(aprobados.get())));
        BigDecimal real = repositorio.buscar(cuenta.cuentaId()).orElseThrow().saldo();

        assertEquals(0, esperado.compareTo(real),
                "el saldo final debe descontar todos los retiros aprobados, sin perder ninguno");
    }
}
