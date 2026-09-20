package cl.duoc.bancoxyz.bff.movimientos.domain;

import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.bff.movimientos.util.FechaLegacyParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Historial de movimientos por cuenta, cargado desde cuentas_anuales.csv al
 * arrancar y ampliable con movimientos nuevos.
 *
 * Al cargar se aplican las mismas reglas que en la migración batch de la
 * semana 3: se corrige "depósito" con tilde, se completa la descripción vacía,
 * y se omiten las filas con fecha no interpretable o monto ≤ 0.
 *
 * Las estructuras son concurrentes porque el registro de movimientos ocurre en
 * hilos de petición que pueden solaparse, a diferencia de la carga inicial.
 */
@Repository
public class MovimientoRepositoryEnMemoria {

    private static final Logger log = LoggerFactory.getLogger(MovimientoRepositoryEnMemoria.class);
    private static final Set<String> TIPOS_VALIDOS = Set.of("compra", "deposito", "pago", "retiro");
    private static final Set<String> TIPOS_EGRESO = Set.of("retiro", "compra", "pago");

    private final Map<Long, List<MovimientoDTO>> movimientosPorCuenta = new ConcurrentHashMap<>();

    @PostConstruct
    void cargarDatos() {
        int leidas = 0;
        int validas = 0;
        int omitidas = 0;
        Resource recurso = new PathMatchingResourcePatternResolver().getResource("classpath:data/cuentas_anuales.csv");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(recurso.getInputStream(), StandardCharsets.UTF_8))) {
            String linea = reader.readLine(); // encabezado
            while ((linea = reader.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                leidas++;
                MovimientoDTO movimiento = parsear(linea);
                if (movimiento != null) {
                    validas++;
                    agregar(movimiento);
                } else {
                    omitidas++;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar cuentas_anuales.csv", ex);
        }
        log.info(">> core-movimientos-service: {} filas leídas = {} movimientos válidos + {} omitidos por datos inconsistentes",
                leidas, validas, omitidas);
        log.info(">> core-movimientos-service: historial cargado para {} cuentas distintas",
                movimientosPorCuenta.size());
    }

    private void agregar(MovimientoDTO movimiento) {
        movimientosPorCuenta
                .computeIfAbsent(movimiento.cuentaId(), id -> new CopyOnWriteArrayList<>())
                .add(movimiento);
    }

    private MovimientoDTO parsear(String linea) {
        String[] campos = linea.split(",", -1);
        if (campos.length < 5) {
            return null;
        }
        try {
            Long cuentaId = Long.parseLong(campos[0].trim());
            String montoTexto = campos[3].trim();
            BigDecimal monto = montoTexto.isBlank() ? null : new BigDecimal(montoTexto);
            return construir(cuentaId, campos[1], campos[2], monto, campos[4]).orElse(null);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Punto único donde se normaliza y valida un movimiento, venga del CSV o
     * de la API. Devuelve vacío si no cumple las reglas del dominio: fecha
     * interpretable, tipo dentro del dominio y monto mayor que cero.
     */
    private Optional<MovimientoDTO> construir(Long cuentaId, String fechaTexto, String tipoTexto,
                                              BigDecimal monto, String descripcion) {
        if (cuentaId == null || monto == null || monto.signum() <= 0) {
            return Optional.empty();
        }
        Optional<LocalDate> fecha = FechaLegacyParser.parsear(fechaTexto);
        if (fecha.isEmpty()) {
            return Optional.empty();
        }
        String tipo = normalizar(tipoTexto);
        if (!TIPOS_VALIDOS.contains(tipo)) {
            return Optional.empty();
        }
        String descripcionFinal = (descripcion == null || descripcion.isBlank())
                ? "Sin descripción"
                : descripcion.trim();
        return Optional.of(new MovimientoDTO(
                cuentaId, fecha.get().format(DateTimeFormatter.ISO_LOCAL_DATE), tipo, monto, descripcionFinal));
    }

    /** El dataset trae "depósito" y "deposito" como el mismo tipo. */
    private String normalizar(String tipo) {
        return tipo == null ? "" : tipo.trim().toLowerCase().replace("ó", "o");
    }

    /** Historial completo, de la fecha más antigua a la más reciente. */
    public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
        return movimientosPorCuenta.getOrDefault(cuentaId, List.of()).stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha))
                .toList();
    }

    /**
     * Los movimientos más recientes primero. El recorte ocurre aquí y no en el
     * BFF para que una petición móvil no obligue a serializar el historial
     * completo.
     */
    public List<MovimientoDTO> obtenerUltimos(Long cuentaId, int limite) {
        return movimientosPorCuenta.getOrDefault(cuentaId, List.of()).stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha).reversed())
                .limit(limite)
                .toList();
    }

    /**
     * Registra un movimiento nuevo aplicando las mismas reglas que la carga
     * del CSV. Devuelve el movimiento ya normalizado, o vacío si no cumple
     * las reglas.
     */
    public Optional<MovimientoDTO> registrar(MovimientoDTO movimiento) {
        if (movimiento == null) {
            return Optional.empty();
        }
        Optional<MovimientoDTO> valido = construir(
                movimiento.cuentaId(), movimiento.fecha(), movimiento.tipoMovimiento(),
                movimiento.monto(), movimiento.descripcion());
        valido.ifPresent(this::agregar);
        return valido;
    }

    public ResumenMovimientosDTO resumir(Long cuentaId) {
        List<MovimientoDTO> movimientos = obtenerMovimientos(cuentaId);
        BigDecimal depositos = movimientos.stream()
                .filter(m -> "deposito".equals(m.tipoMovimiento()))
                .map(MovimientoDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal egresos = movimientos.stream()
                .filter(m -> TIPOS_EGRESO.contains(m.tipoMovimiento()))
                .map(MovimientoDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String ultimaFecha = movimientos.isEmpty() ? null : movimientos.get(movimientos.size() - 1).fecha();
        return new ResumenMovimientosDTO(cuentaId, movimientos.size(), depositos, egresos, ultimaFecha);
    }
}
