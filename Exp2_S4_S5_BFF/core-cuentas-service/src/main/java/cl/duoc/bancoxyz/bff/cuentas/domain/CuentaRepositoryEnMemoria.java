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
 * Almacén en memoria de las cuentas, cargado desde intereses.csv al arrancar.
 *
 * Las reglas de validación son las mismas que se aplicaron en la migración
 * batch de la semana 3: saldo ≥ 0, edad entre 18 y 120, y tipo dentro del
 * dominio conocido. Una fila que no las cumple queda fuera del servicio.
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
                    cuentas.put(registro.get().cuentaId(), registro.get());
                } else {
                    omitidas++;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar intereses.csv", ex);
        }
        log.info(">> core-cuentas-service: {} filas leídas = {} válidas + {} omitidas por datos inconsistentes",
                leidas, validas, omitidas);
        log.info(">> core-cuentas-service: {} cuentas distintas cargadas", cuentas.size());
    }

    /**
     * El dataset repite la misma cuenta en varias filas con valores que no
     * siempre coinciden, y no trae fecha para desempatar: se conserva la última
     * fila válida de cada cuenta.
     */
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
            if (saldo.signum() < 0 || edad < 18 || edad > 120 || !TIPOS_VALIDOS.contains(tipo)) {
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
     * Aplica un débito. Devuelve vacío si la cuenta no existe; si existe,
     * devuelve el resultado aprobado o rechazado según haya fondos.
     *
     * Se usa {@code compute} para que leer el saldo, validar los fondos y
     * escribir el nuevo saldo sean una sola operación atómica sobre la clave.
     * Sincronizar sobre el registro leído antes no sirve: el registro es
     * inmutable y cada débito lo reemplaza por otra instancia, así que dos
     * retiros simultáneos pueden partir del mismo saldo y perderse uno.
     */
    public Optional<ResultadoDebito> debitar(Long cuentaId, BigDecimal monto) {
        AtomicReference<ResultadoDebito> resultado = new AtomicReference<>();

        cuentas.compute(cuentaId, (id, registro) -> {
            if (registro == null) {
                return null;
            }
            if (registro.saldo().compareTo(monto) < 0) {
                resultado.set(new ResultadoDebito(false, "Fondos insuficientes", registro.saldo()));
                return registro;
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
