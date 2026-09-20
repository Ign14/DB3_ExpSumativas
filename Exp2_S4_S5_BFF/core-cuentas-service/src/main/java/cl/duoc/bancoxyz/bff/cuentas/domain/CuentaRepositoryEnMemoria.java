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
import java.util.concurrent.atomic.AtomicReference;

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
        int validas = 0;
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
                    validas++;
                    // El dataset legacy repite la misma cuenta en varias filas y no
                    // trae fecha para desempatar: se conserva la ultima fila valida.
                    cuentas.put(registro.get().cuentaId(), registro.get());
                } else {
                    omitidas++;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar intereses.csv", ex);
        }
        log.info(">> core-cuentas-service: {} filas leidas = {} validas + {} omitidas por datos inconsistentes",
                leidas, validas, omitidas);
        log.info(">> core-cuentas-service: {} cuentas distintas cargadas (el dataset repite la misma cuenta en varias filas; se conserva la ultima valida de cada una)",
                cuentas.size());
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
     *
     * <p>Se usa {@link ConcurrentHashMap#compute} y no un bloque
     * {@code synchronized} sobre el valor leido previamente: el registro es
     * inmutable y cada debito lo reemplaza por una instancia nueva, asi que
     * un lock tomado sobre la instancia vieja no impide que dos retiros
     * simultaneos lean el mismo saldo de partida y se pise uno al otro
     * (lost update). {@code compute} garantiza que la lectura del saldo, la
     * validacion de fondos y la escritura del nuevo saldo ocurran como una
     * sola operacion atomica sobre la clave.</p>
     */
    public Optional<ResultadoDebito> debitar(Long cuentaId, BigDecimal monto) {
        // El resultado se publica desde dentro de compute(), que es donde se
        // decide si el debito se aplica o se rechaza.
        AtomicReference<ResultadoDebito> resultado = new AtomicReference<>();

        cuentas.compute(cuentaId, (id, registro) -> {
            if (registro == null) {
                return null; // cuenta inexistente: no se crea nada
            }
            if (registro.saldo().compareTo(monto) < 0) {
                resultado.set(new ResultadoDebito(false, "Fondos insuficientes", registro.saldo()));
                return registro; // se deja el registro intacto
            }
            CuentaRegistro actualizado = registro.conSaldo(registro.saldo().subtract(monto));
            resultado.set(new ResultadoDebito(true, null, actualizado.saldo()));
            return actualizado;
        });

        return Optional.ofNullable(resultado.get());
    }

    public record ResultadoDebito(boolean aprobado, String motivoRechazo, BigDecimal saldoResultante) {
    }
}
