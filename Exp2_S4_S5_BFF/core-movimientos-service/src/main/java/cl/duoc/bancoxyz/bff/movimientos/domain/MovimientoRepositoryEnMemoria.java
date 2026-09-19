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

/**
 * Carga cuentas_anuales.csv (historial de movimientos por cuenta) en
 * memoria. Reutiliza las mismas reglas de correccion/omision que el
 * MovimientoItemProcessor de la migracion batch: corrige "depósito" con
 * tilde, completa descripciones vacias, y omite fecha no interpretable o
 * monto <= 0.
 */
@Repository
public class MovimientoRepositoryEnMemoria {

    private static final Logger log = LoggerFactory.getLogger(MovimientoRepositoryEnMemoria.class);
    private static final Set<String> TIPOS_VALIDOS = Set.of("compra", "deposito", "pago", "retiro");
    private static final Set<String> TIPOS_DEPOSITO = Set.of("deposito");

    private final Map<Long, List<MovimientoDTO>> movimientosPorCuenta = new HashMap<>();

    @PostConstruct
    void cargarDatos() {
        int leidas = 0;
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
                    movimientosPorCuenta.computeIfAbsent(movimiento.cuentaId(), id -> new ArrayList<>()).add(movimiento);
                } else {
                    omitidas++;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar cuentas_anuales.csv", ex);
        }
        int total = movimientosPorCuenta.values().stream().mapToInt(List::size).sum();
        log.info(">> core-movimientos-service: {} filas leidas, {} movimientos cargados validos, {} omitidos por datos inconsistentes",
                leidas, total, omitidas);
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
            String tipo = normalizar(campos[2].trim().toLowerCase());
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
            String descripcion = campos.length > 4 ? campos[4].trim() : "";
            if (descripcion.isBlank()) {
                descripcion = "Sin descripción";
            }
            return new MovimientoDTO(cuentaId, fecha.get().format(DateTimeFormatter.ISO_LOCAL_DATE), tipo, monto, descripcion);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizar(String tipo) {
        return TIPOS_DEPOSITO.contains(tipo.replace("ó", "o")) ? "deposito" : tipo.replace("ó", "o");
    }

    public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
        return movimientosPorCuenta.getOrDefault(cuentaId, List.of()).stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha))
                .toList();
    }

    public boolean existeCuenta(Long cuentaId) {
        return movimientosPorCuenta.containsKey(cuentaId);
    }

    public ResumenMovimientosDTO resumir(Long cuentaId) {
        List<MovimientoDTO> movimientos = obtenerMovimientos(cuentaId);
        BigDecimal depositos = movimientos.stream()
                .filter(m -> "deposito".equals(m.tipoMovimiento()))
                .map(MovimientoDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal retiros = movimientos.stream()
                .filter(m -> Set.of("retiro", "compra", "pago").contains(m.tipoMovimiento()))
                .map(MovimientoDTO::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String ultimaFecha = movimientos.isEmpty() ? null : movimientos.get(movimientos.size() - 1).fecha();
        return new ResumenMovimientosDTO(cuentaId, movimientos.size(), depositos, retiros, ultimaFecha);
    }
}
