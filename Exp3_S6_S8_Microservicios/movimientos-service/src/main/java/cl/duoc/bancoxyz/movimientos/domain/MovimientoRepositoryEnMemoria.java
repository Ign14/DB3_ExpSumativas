package cl.duoc.bancoxyz.movimientos.domain;

import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.movimientos.util.FechaLegacyParser;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Historial de movimientos por cuenta, cargado desde cuentas_anuales.csv al
 * arrancar y ampliable con movimientos nuevos.
 *
 * Al cargar se aplican las mismas reglas que en la migracion batch de la
 * semana 3: se corrige "deposito" con tilde, se completa la descripcion vacia,
 * y se omiten las filas con fecha no interpretable o monto menor o igual a 0.
 *
 * Las estructuras son concurrentes porque ahora hay tres caminos de escritura
 * que pueden solaparse: la carga inicial, el registro por API y el consumidor
 * de la cola, que corre en un hilo del contenedor de JMS y no en un hilo de
 * peticion.
 */
@Repository
public class MovimientoRepositoryEnMemoria {

    private static final Logger log = LoggerFactory.getLogger(MovimientoRepositoryEnMemoria.class);
    private static final Set<String> TIPOS_VALIDOS = Set.of("compra", "deposito", "pago", "retiro");
    private static final Set<String> TIPOS_EGRESO = Set.of("retiro", "compra", "pago");
    private static final int MAX_DESCRIPCION = 200;

    private final Map<Long, List<MovimientoDTO>> movimientosPorCuenta = new ConcurrentHashMap<>();

    @PostConstruct
    public void cargarDatos() {
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
        log.info(">> movimientos-service: {} filas leidas = {} movimientos validos + {} omitidos por datos inconsistentes",
                leidas, validas, omitidas);
        log.info(">> movimientos-service: historial cargado para {} cuentas distintas",
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
     * Punto unico donde se normaliza y valida un movimiento, venga del CSV, de
     * la API o de un evento de la cola. Devuelve vacio si no cumple las reglas
     * del dominio: fecha interpretable, tipo dentro del dominio y monto mayor
     * que cero.
     *
     * Tener las tres puertas de entrada pasando por aqui es lo que impide que
     * una sea mas permisiva que las otras.
     */
    private Optional<MovimientoDTO> construir(Long cuentaId, String fechaTexto, String tipoTexto,
                                              BigDecimal monto, String descripcion) {
        // El identificador tiene que ser un identificador: un cuentaId negativo
        // o cero no corresponde a ninguna cuenta del dataset y crearia historial
        // para una cuenta que no puede existir.
        if (cuentaId == null || cuentaId <= 0 || monto == null || monto.signum() <= 0) {
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
                ? "Sin descripcion"
                : descripcion.trim();
        // La descripcion viene de un CSV o de un cuerpo JSON que el cliente
        // controla: se acota para que nadie pueda hacer crecer el historial en
        // memoria con un solo movimiento.
        if (descripcionFinal.length() > MAX_DESCRIPCION) {
            descripcionFinal = descripcionFinal.substring(0, MAX_DESCRIPCION);
        }
        return Optional.of(new MovimientoDTO(
                cuentaId, fecha.get().format(DateTimeFormatter.ISO_LOCAL_DATE), tipo, monto, descripcionFinal));
    }

    /** El dataset trae "deposito" con y sin tilde como el mismo tipo. */
    private String normalizar(String tipo) {
        return tipo == null ? "" : tipo.trim().toLowerCase().replace("ó", "o");
    }

    /** Historial completo, de la fecha mas antigua a la mas reciente. */
    public List<MovimientoDTO> obtenerMovimientos(Long cuentaId) {
        return movimientosPorCuenta.getOrDefault(cuentaId, List.of()).stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha))
                .toList();
    }

    public List<MovimientoDTO> obtenerUltimos(Long cuentaId, int limite) {
        return movimientosPorCuenta.getOrDefault(cuentaId, List.of()).stream()
                .sorted(Comparator.comparing(MovimientoDTO::fecha).reversed())
                .limit(limite)
                .toList();
    }

    public boolean existeHistorial(Long cuentaId) {
        return movimientosPorCuenta.containsKey(cuentaId);
    }

    /**
     * Registra un movimiento nuevo aplicando las mismas reglas que la carga del
     * CSV. Devuelve el movimiento ya normalizado, o vacio si no cumple.
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
        return resumir(cuentaId, obtenerMovimientos(cuentaId));
    }

    /**
     * Resume una lista ya obtenida, en vez de volver a leer el repositorio.
     *
     * Hace falta porque el consumidor de la cola escribe desde otro hilo: quien
     * necesite devolver el detalle y el resumen en la misma respuesta tiene que
     * calcular los dos sobre la misma lectura, o puede entregar un resumen que
     * cuenta un movimiento que el detalle no trae.
     */
    public ResumenMovimientosDTO resumir(Long cuentaId, List<MovimientoDTO> movimientos) {
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
