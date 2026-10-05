package cl.duoc.bancoxyz.movimientos.domain;

import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.dto.ResumenMovimientosDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovimientoRepositoryEnMemoriaTest {

    private MovimientoRepositoryEnMemoria repositorio;
    private Long cuentaConHistorial;

    @BeforeEach
    void cargar() {
        repositorio = new MovimientoRepositoryEnMemoria();
        repositorio.cargarDatos();
        cuentaConHistorial = 103L;
        assertTrue(repositorio.existeHistorial(cuentaConHistorial), "la cuenta 103 deberia venir en el dataset");
    }

    @Test
    @DisplayName("Todo movimiento cargado cumple las reglas del dominio")
    void cargaSoloMovimientosValidos() {
        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(cuentaConHistorial);
        assertFalse(movimientos.isEmpty());
        movimientos.forEach(m -> {
            assertTrue(m.monto().signum() > 0, "monto no positivo: " + m.monto());
            assertTrue(Set.of("compra", "deposito", "pago", "retiro").contains(m.tipoMovimiento()),
                    "tipo fuera del dominio: " + m.tipoMovimiento());
            assertTrue(m.fecha().matches("\\d{4}-\\d{2}-\\d{2}"), "fecha no normalizada: " + m.fecha());
            assertFalse(m.descripcion().isBlank(), "la descripcion vacia debe completarse");
        });
    }

    @Test
    @DisplayName("El historial completo viene en orden cronologico")
    void ordenCronologico() {
        List<String> fechas = repositorio.obtenerMovimientos(cuentaConHistorial).stream()
                .map(MovimientoDTO::fecha).toList();
        assertEquals(fechas.stream().sorted().toList(), fechas);
    }

    @Test
    @DisplayName("Los ultimos N vienen de la fecha mas reciente hacia atras")
    void ultimosRecortados() {
        List<MovimientoDTO> completo = repositorio.obtenerMovimientos(cuentaConHistorial);
        List<MovimientoDTO> ultimos = repositorio.obtenerUltimos(cuentaConHistorial, 3);

        assertEquals(3, ultimos.size());
        List<String> fechas = ultimos.stream().map(MovimientoDTO::fecha).toList();
        assertEquals(fechas.stream().sorted(Comparator.reverseOrder()).toList(), fechas);
        assertEquals(completo.get(completo.size() - 1).fecha(), fechas.get(0),
                "el primero de los ultimos N debe ser el mas reciente del historial");
    }

    @Test
    @DisplayName("Registrar por API aplica las mismas reglas que la carga del CSV")
    void registroValidaIgualQueLaCarga() {
        int antes = repositorio.obtenerMovimientos(cuentaConHistorial).size();

        assertTrue(repositorio.registrar(new MovimientoDTO(
                cuentaConHistorial, "2024-05-05", "deposito", new BigDecimal("250"), "Abono")).isPresent());

        // Fecha imposible, tipo fuera del dominio, monto no positivo y nulo.
        assertTrue(repositorio.registrar(new MovimientoDTO(
                cuentaConHistorial, "31/02/2024", "deposito", new BigDecimal("250"), "x")).isEmpty());
        assertTrue(repositorio.registrar(new MovimientoDTO(
                cuentaConHistorial, "2024-05-05", "transferencia", new BigDecimal("250"), "x")).isEmpty());
        assertTrue(repositorio.registrar(new MovimientoDTO(
                cuentaConHistorial, "2024-05-05", "deposito", BigDecimal.ZERO, "x")).isEmpty());
        assertTrue(repositorio.registrar(new MovimientoDTO(
                cuentaConHistorial, "2024-05-05", "deposito", null, "x")).isEmpty());
        assertTrue(repositorio.registrar(null).isEmpty());

        assertEquals(antes + 1, repositorio.obtenerMovimientos(cuentaConHistorial).size(),
                "solo el movimiento valido debio quedar registrado");
    }

    @Test
    @DisplayName("Al registrar se normaliza igual que al cargar: tilde y descripcion vacia")
    void normalizaAlRegistrar() {
        MovimientoDTO registrado = repositorio.registrar(new MovimientoDTO(
                777_001L, "05/05/2024", "Depósito", new BigDecimal("100"), "   ")).orElseThrow();

        assertEquals("deposito", registrado.tipoMovimiento());
        assertEquals("Sin descripcion", registrado.descripcion());
        assertEquals("2024-05-05", registrado.fecha());
    }

    @Test
    @DisplayName("El resumen suma depositos aparte de los egresos")
    void resumenSeparaDepositosDeEgresos() {
        MovimientoRepositoryEnMemoria vacio = new MovimientoRepositoryEnMemoria();
        Long cuenta = 888_001L;
        vacio.registrar(new MovimientoDTO(cuenta, "2024-01-01", "deposito", new BigDecimal("1000"), "a"));
        vacio.registrar(new MovimientoDTO(cuenta, "2024-01-02", "retiro", new BigDecimal("100"), "b"));
        vacio.registrar(new MovimientoDTO(cuenta, "2024-01-03", "compra", new BigDecimal("200"), "c"));
        vacio.registrar(new MovimientoDTO(cuenta, "2024-01-04", "pago", new BigDecimal("300"), "d"));

        ResumenMovimientosDTO resumen = vacio.resumir(cuenta);

        assertEquals(4, resumen.totalMovimientos());
        assertEquals(0, new BigDecimal("1000").compareTo(resumen.totalDepositos()));
        assertEquals(0, new BigDecimal("600").compareTo(resumen.totalEgresos()),
                "totalEgresos agrupa retiro, compra y pago");
        assertEquals("2024-01-04", resumen.ultimaFecha());
    }

    @Test
    @DisplayName("Un identificador de cuenta no positivo se rechaza")
    void identificadorInvalido() {
        assertTrue(repositorio.registrar(new MovimientoDTO(
                0L, "2024-05-05", "deposito", new BigDecimal("100"), "x")).isEmpty());
        assertTrue(repositorio.registrar(new MovimientoDTO(
                -5L, "2024-05-05", "deposito", new BigDecimal("100"), "x")).isEmpty());
    }

    @Test
    @DisplayName("La descripcion se acota: el cliente no decide cuanta memoria ocupa un movimiento")
    void descripcionAcotada() {
        String larguisima = "x".repeat(5_000);

        MovimientoDTO registrado = repositorio.registrar(new MovimientoDTO(
                999_001L, "2024-05-05", "deposito", new BigDecimal("100"), larguisima)).orElseThrow();

        assertEquals(200, registrado.descripcion().length());
    }

    @Test
    @DisplayName("El resumen se puede calcular sobre una lista ya leida, sin volver al repositorio")
    void resumenSobreListaDada() {
        List<MovimientoDTO> historial = repositorio.obtenerMovimientos(cuentaConHistorial);

        ResumenMovimientosDTO sobreLista = repositorio.resumir(cuentaConHistorial, historial);
        ResumenMovimientosDTO sobreRepositorio = repositorio.resumir(cuentaConHistorial);

        assertEquals(historial.size(), sobreLista.totalMovimientos(),
                "el resumen tiene que cuadrar con el detalle que se va a devolver");
        assertEquals(sobreRepositorio, sobreLista);
    }

    @Test
    @DisplayName("Una cuenta sin historial no existe y su resumen queda en cero")
    void cuentaSinHistorial() {
        assertFalse(repositorio.existeHistorial(999_999L));
        assertTrue(repositorio.obtenerMovimientos(999_999L).isEmpty());
        ResumenMovimientosDTO resumen = repositorio.resumir(999_999L);
        assertEquals(0, resumen.totalMovimientos());
        assertEquals(0, BigDecimal.ZERO.compareTo(resumen.totalDepositos()));
    }
}
