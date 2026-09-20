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
 * Carga cuentas_anuales.csv (historial de movimientos por cuenta) en
 * memoria y permite registrar movimientos nuevos (los que genera, por
 * ejemplo, un retiro por cajero).
 *
 * <p>Reutiliza las mismas reglas de correccion/omision que el
 * MovimientoItemProcessor de la migracion batch: corrige "depósito" con
 * tilde, completa descripciones vacias, y omite fecha no interpretable o
 * monto &lt;= 0.</p>
 *
 * <p>Las estructuras son concurrentes porque, a diferencia de la carga
 * inicial (un solo hilo en el arranque), el registro de movimientos ocurre
 * en hilos de peticion que pueden solaparse.</p>
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
        log.info(">> core-movimientos-service: {} filas leidas = {} movimientos validos + {} omitidos por datos inconsistentes",
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
            Optional<LocalDate> fecha = FechaLegacyParser.parsear(campos[1]);
            if (fecha.isEmpty()) {
                return null;
            }
            String tipo = normalizar(campos[2]);
            if (!TIPOS_VALIDOS.contains(tipo)) {
                return null;
            }
            String montoTexto = campos[3].trim();
            if (montoTexto.isBlank()) {
                return null;
            }
            BigDecimal monto = new BigDecimal(montoTexto);
            if (monto.signum() <= 0) {
                return null;
            }
            String descripcion = campos[4].trim();
            if (descripcion.isBlank()) {
                descripcion = "Sin descripción";
            }
            return new MovimientoDTO(cuentaId, fecha.get().format(DateTimeFormatter.ISO_LOCAL_DATE), tipo, monto, descripcion);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Corrige la variante con tilde del dataset legacy ("depósito" -> "deposito"). */
    private String normalizar(String tipo) {
        return tipo.trim().toLowerCase().replace("ó", "o");
    }

    /** Historial completo, de la fecha mas antigua a la mas reciente. */
    public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
        return movimientosPorCuenta.getOrDefault(cuentaId, List.of()).stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha))
                .toList();
    }

    /**
     * Los {@code limite} movimientos mas recientes, de mas nuevo a mas
     * antiguo. El recorte ocurre aqui y no en el BFF para que el canal que
     * solo necesita unos pocos movimientos no obligue al core a serializar
     * el historial completo.
     */
    public List<MovimientoDTO> obtenerUltimos(Long cuentaId, int limite) {
        return movimientosPorCuenta.getOrDefault(cuentaId, List.of()).stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha).reversed())
                .limit(limite)
                .toList();
    }

    /** Registra un movimiento nuevo (por ejemplo, el retiro de un cajero). */
    public MovimientoDTO registrar(MovimientoDTO movimiento) {
        agregar(movimiento);
        return movimiento;
    }

    public ResumenMovimientosDTO resumir(Long cuentaId) {
        List<MovimientoDTO> movimientos = obtenerMovimientos(cuentaId);
        BigDecimal depositos = movimientos.stream()
                .filter(m -> "deposito".equals(m.tipoMovimiento()))
                .map(MovimientoDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal retiros = movimientos.stream()
                .filter(m -> TIPOS_EGRESO.contains(m.tipoMovimiento()))
                .map(MovimientoDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String ultimaFecha = movimientos.isEmpty() ? null : movimientos.get(movimientos.size() - 1).fecha();
        return new ResumenMovimientosDTO(cuentaId, movimientos.size(), depositos, retiros, ultimaFecha);
    }
}
