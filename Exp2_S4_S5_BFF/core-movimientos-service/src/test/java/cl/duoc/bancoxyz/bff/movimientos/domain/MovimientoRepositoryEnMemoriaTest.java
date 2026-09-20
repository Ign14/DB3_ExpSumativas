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

    private MovimientoRepositoryEnMemoria repositorio;
    private Long cuentaConMovimientos;

    @BeforeEach
    void setUp() {
        repositorio = new MovimientoRepositoryEnMemoria();
        repositorio.cargarDatos();
        cuentaConMovimientos = 103L;
    }

    @Test
    @DisplayName("Todos los movimientos cargados cumplen las reglas del dataset legacy")
    void cargaSoloMovimientosValidos() {
        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(cuentaConMovimientos);

        assertFalse(movimientos.isEmpty());
        assertTrue(movimientos.stream().allMatch(m -> m.monto().signum() > 0),
                "no puede quedar ningun movimiento con monto <= 0");
        assertTrue(movimientos.stream().noneMatch(m -> m.descripcion() == null || m.descripcion().isBlank()),
                "las descripciones vacias deben quedar completadas");
        assertTrue(movimientos.stream().noneMatch(m -> m.tipoMovimiento().contains("ó")),
                "el tipo con tilde del dataset debe quedar normalizado");
    }

    @Test
    @DisplayName("El historial completo viene ordenado de la fecha mas antigua a la mas reciente")
    void historialOrdenadoAscendente() {
        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(cuentaConMovimientos);

        assertEquals(
                movimientos.stream().sorted(Comparator.comparing(MovimientoDTO::fecha)).toList(),
                movimientos);
    }

    @Test
    @DisplayName("obtenerUltimos recorta en origen y devuelve los mas recientes primero")
    void ultimosMovimientosRecortadosYDescendentes() {
        List<MovimientoDTO> completo = repositorio.obtenerMovimientos(cuentaConMovimientos);
        List<MovimientoDTO> ultimos = repositorio.obtenerUltimos(cuentaConMovimientos, 3);

        assertEquals(3, ultimos.size());
        assertTrue(ultimos.size() < completo.size(), "el recorte debe devolver menos que el historial completo");
        assertEquals(completo.get(completo.size() - 1).fecha(), ultimos.get(0).fecha(),
                "el primero de la lista recortada debe ser el movimiento mas reciente");
    }

    @Test
    @DisplayName("Una cuenta sin movimientos devuelve lista vacia y resumen en cero, no un error")
    void cuentaSinMovimientos() {
        assertTrue(repositorio.obtenerMovimientos(999_999L).isEmpty());

        ResumenMovimientosDTO resumen = repositorio.resumir(999_999L);
        assertEquals(0, resumen.cantidadMovimientos());
        assertEquals(0, BigDecimal.ZERO.compareTo(resumen.totalDepositos()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resumen.totalRetiros()));
        assertNull(resumen.ultimoMovimientoFecha());
    }

    @Test
    @DisplayName("Registrar un movimiento lo deja visible en el historial y en el resumen")
    void registrarMovimientoActualizaHistorialYResumen() {
        ResumenMovimientosDTO antes = repositorio.resumir(cuentaConMovimientos);
        BigDecimal monto = new BigDecimal("777");

        repositorio.registrar(new MovimientoDTO(
                cuentaConMovimientos, "2025-01-15", "retiro", monto, "Retiro por cajero automático"));

        ResumenMovimientosDTO despues = repositorio.resumir(cuentaConMovimientos);
        assertEquals(antes.cantidadMovimientos() + 1, despues.cantidadMovimientos());
        assertEquals(0, antes.totalRetiros().add(monto).compareTo(despues.totalRetiros()));
        assertEquals("2025-01-15", despues.ultimoMovimientoFecha());
        assertEquals("2025-01-15", repositorio.obtenerUltimos(cuentaConMovimientos, 1).get(0).fecha());
    }
}
