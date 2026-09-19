package cl.duoc.bancoxyz.bff.cuentas.domain;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carga intereses.csv (dataset legacy) en memoria al arrancar y expone las
 * operaciones de lectura y debito sobre el saldo de cada cuenta.
 *
 * <p>Reutiliza las mismas reglas de validacion que ya se aplicaron en la
 * migracion batch de la semana 3 (saldo &gt;= 0, edad en [18,120], tipo en
 * {ahorro, prestamo, hipoteca}): una fila que no las cumple queda fuera del
 * servicio, igual que quedaba fuera del batch como anomalia.</p>
 */
@Repository
public class CuentaRepositoryEnMemoria {

    private static final Logger log = LoggerFactory.getLogger(CuentaRepositoryEnMemoria.class);
    private static final Set<String> TIPOS_VALIDOS = Set.of("ahorro", "prestamo", "hipoteca");

    private final Map<Long, CuentaRegistro> cuentas = new ConcurrentHashMap<>();

    @PostConstruct
    void cargarDatos() {
        int leidas = 0;
        int omitidas = 0;
        Resource recurso = new PathMatchingResourcePatternResolver().getResource("classpath:data/intereses.csv");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(recurso.getInputStream(), StandardCharsets.UTF_8))) {
            String linea = reader.readLine(); // encabezado
            while ((linea = reader.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                leidas++;
                Optional<CuentaRegistro> registro = parsear(linea);
                if (registro.isPresent()) {
                    cuentas.put(registro.get().cuentaId(), registro.get());
                } else {
                    omitidas++;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar intereses.csv", ex);
        }
        log.info(">> core-cuentas-service: {} filas leidas, {} cargadas validas, {} omitidas por datos inconsistentes",
                leidas, cuentas.size(), omitidas);
    }

    private Optional<CuentaRegistro> parsear(String linea) {
        String[] campos = linea.split(",", -1);
        if (campos.length < 5) {
            return Optional.empty();
        }
        try {
            Long cuentaId = Long.parseLong(campos[0].trim());
            String nombre = campos[1].trim();
            if (nombre.isBlank()) {
                nombre = "Sin nombre registrado";
            }
            String saldoTexto = campos[2].trim();
            String edadTexto = campos[3].trim();
            String tipo = campos[4].trim().toLowerCase();

            if (saldoTexto.isBlank() || edadTexto.isBlank()) {
                return Optional.empty();
            }
            BigDecimal saldo = new BigDecimal(saldoTexto);
            int edad = Integer.parseInt(edadTexto);
            if (saldo.signum() < 0) {
                return Optional.empty();
            }
            if (edad < 18 || edad > 120) {
                return Optional.empty();
            }
            if (!TIPOS_VALIDOS.contains(tipo)) {
                return Optional.empty();
            }
            return Optional.of(new CuentaRegistro(cuentaId, nombre, edad, tipo, saldo));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public List<CuentaDTO> listar() {
        return cuentas.values().stream()
                .sorted((a, b) -> Long.compare(a.cuentaId(), b.cuentaId()))
                .map(CuentaRegistro::aDto)
                .toList();
    }

    public Optional<CuentaDTO> buscar(Long cuentaId) {
        return Optional.ofNullable(cuentas.get(cuentaId)).map(CuentaRegistro::aDto);
    }

    /**
     * Aplica un debito de forma atomica sobre el registro en memoria.
     * Devuelve empty si la cuenta no existe; si existe, siempre devuelve un
     * resultado (aprobado o no segun haya o no fondos suficientes).
     */
    public Optional<ResultadoDebito> debitar(Long cuentaId, BigDecimal monto) {
        CuentaRegistro registro = cuentas.get(cuentaId);
        if (registro == null) {
            return Optional.empty();
        }
        synchronized (registro) {
            if (registro.saldo().compareTo(monto) < 0) {
                return Optional.of(new ResultadoDebito(false, "Fondos insuficientes", registro.saldo()));
            }
            CuentaRegistro actualizado = registro.conSaldo(registro.saldo().subtract(monto));
            cuentas.put(cuentaId, actualizado);
            return Optional.of(new ResultadoDebito(true, null, actualizado.saldo()));
        }
    }

    public record ResultadoDebito(boolean aprobado, String motivoRechazo, BigDecimal saldoResultante) {
    }
}
