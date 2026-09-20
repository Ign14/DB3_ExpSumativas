package cl.duoc.bancoxyz.bff.movimientos.domain;

import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MovimientoRepositoryEnMemoriaTest {

    private static final Long CUENTA_CON_MOVIMIENTOS = 103L;

    private MovimientoRepositoryEnMemoria repositorio;

    @BeforeEach
    void setUp() {
        repositorio = new MovimientoRepositoryEnMemoria();
        repositorio.cargarDatos();
    }

    @Test
    @DisplayName("Todos los movimientos cargados cumplen las reglas de validación")
    void cargaSoloMovimientosValidos() {
        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(CUENTA_CON_MOVIMIENTOS);

        assertFalse(movimientos.isEmpty());
        assertTrue(movimientos.stream().allMatch(m -> m.monto().signum() > 0),
                "no puede quedar ningún movimiento con monto <= 0");
        assertTrue(movimientos.stream().noneMatch(m -> m.descripcion() == null || m.descripcion().isBlank()),
                "las descripciones vacías deben quedar completadas");
        assertTrue(movimientos.stream().noneMatch(m -> m.tipoMovimiento().contains("ó")),
                "el tipo con tilde del dataset debe quedar normalizado");
    }

    @Test
    @DisplayName("El historial completo viene de la fecha más antigua a la más reciente")
    void historialOrdenadoAscendente() {
        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(CUENTA_CON_MOVIMIENTOS);

        assertEquals(
                movimientos.stream().sorted(Comparator.comparing(MovimientoDTO::fecha)).toList(),
                movimientos);
    }

    @Test
    @DisplayName("obtenerUltimos recorta y devuelve los más recientes primero")
    void ultimosMovimientosRecortadosYDescendentes() {
        List<MovimientoDTO> completo = repositorio.obtenerMovimientos(CUENTA_CON_MOVIMIENTOS);
        List<MovimientoDTO> ultimos = repositorio.obtenerUltimos(CUENTA_CON_MOVIMIENTOS, 3);

        assertEquals(3, ultimos.size());
        assertTrue(ultimos.size() < completo.size());
        assertEquals(completo.get(completo.size() - 1).fecha(), ultimos.get(0).fecha());
    }

    @Test
    @DisplayName("Una cuenta sin movimientos devuelve lista vacía y resumen en cero, no un error")
    void cuentaSinMovimientos() {
        assertTrue(repositorio.obtenerMovimientos(999_999L).isEmpty());

        ResumenMovimientosDTO resumen = repositorio.resumir(999_999L);
        assertEquals(0, resumen.cantidadMovimientos());
        assertEquals(0, BigDecimal.ZERO.compareTo(resumen.totalDepositos()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resumen.totalEgresos()));
        assertNull(resumen.ultimoMovimientoFecha());
    }

    @Test
    @DisplayName("El resumen suma como egresos los retiros, compras y pagos")
    void resumenAgrupaTodosLosEgresos() {
        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(CUENTA_CON_MOVIMIENTOS);
        BigDecimal egresosEsperados = movimientos.stream()
                .filter(m -> List.of("retiro", "compra", "pago").contains(m.tipoMovimiento()))
                .map(MovimientoDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ResumenMovimientosDTO resumen = repositorio.resumir(CUENTA_CON_MOVIMIENTOS);

        assertEquals(0, egresosEsperados.compareTo(resumen.totalEgresos()));
        assertEquals(movimientos.size(), resumen.cantidadMovimientos());
    }

    @Test
    @DisplayName("Registrar un movimiento lo deja visible en el historial y en el resumen")
    void registrarMovimientoActualizaHistorialYResumen() {
        ResumenMovimientosDTO antes = repositorio.resumir(CUENTA_CON_MOVIMIENTOS);
        BigDecimal monto = new BigDecimal("777");

        repositorio.registrar(new MovimientoDTO(
                CUENTA_CON_MOVIMIENTOS, "2025-01-15", "retiro", monto, "Retiro por cajero automático"));

        ResumenMovimientosDTO despues = repositorio.resumir(CUENTA_CON_MOVIMIENTOS);
        assertEquals(antes.cantidadMovimientos() + 1, despues.cantidadMovimientos());
        assertEquals(0, antes.totalEgresos().add(monto).compareTo(despues.totalEgresos()));
        assertEquals("2025-01-15", despues.ultimoMovimientoFecha());
        assertEquals("2025-01-15", repositorio.obtenerUltimos(CUENTA_CON_MOVIMIENTOS, 1).get(0).fecha());
    }
}
