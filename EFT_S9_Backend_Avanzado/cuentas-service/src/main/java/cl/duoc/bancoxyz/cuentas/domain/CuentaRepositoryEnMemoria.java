package cl.duoc.bancoxyz.cuentas.domain;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
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
 * Almacen en memoria de las cuentas, cargado desde intereses_trimestrales.csv al arrancar.
 *
 * Las reglas de validacion son las mismas que se aplicaron en la migracion
 * batch de la semana 3 y en las APIs de la semana 5: saldo mayor o igual a 0,
 * edad entre 18 y 120, y tipo dentro del dominio conocido. Una fila que no las
 * cumple queda fuera del servicio.
 */
@Repository
public class CuentaRepositoryEnMemoria {

    private static final Logger log = LoggerFactory.getLogger(CuentaRepositoryEnMemoria.class);
    private static final Set<String> TIPOS_VALIDOS = Set.of("ahorro", "prestamo", "hipoteca");

    private final Map<Long, CuentaRegistro> cuentas = new ConcurrentHashMap<>();

    @PostConstruct
    public void cargarDatos() {
        int leidas = 0;
        int validas = 0;
        int omitidas = 0;
        Resource recurso = new PathMatchingResourcePatternResolver().getResource("classpath:data/intereses_trimestrales.csv");
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
            throw new IllegalStateException("No se pudo cargar intereses_trimestrales.csv", ex);
        }
        log.info(">> cuentas-service: {} filas leidas = {} validas + {} omitidas por datos inconsistentes",
                leidas, validas, omitidas);
        log.info(">> cuentas-service: {} cuentas distintas cargadas", cuentas.size());
    }

    /**
     * El dataset repite la misma cuenta en varias filas con valores que no
     * siempre coinciden, y no trae fecha para desempatar: se conserva la ultima
     * fila valida de cada cuenta.
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
     * Aplica un debito. Devuelve vacio si la cuenta no existe; si existe,
     * devuelve el resultado aprobado o rechazado segun haya fondos.
     *
     * Se usa {@code compute} para que leer el saldo, validar los fondos y
     * escribir el nuevo saldo sean una sola operacion atomica sobre la clave.
     * Sincronizar sobre el registro leido antes no sirve: el registro es
     * inmutable y cada debito lo reemplaza por otra instancia, asi que dos
     * retiros simultaneos pueden partir del mismo saldo y perderse uno.
     *
     * Las dos invariantes del dominio se comprueban aqui dentro, no en la capa de
     * servicio: el monto tiene que ser positivo y el saldo no puede quedar
     * negativo. Dejarlas afuera parece inofensivo mientras haya un solo llamador,
     * pero un monto negativo en un {@code subtract} aumenta el saldo, asi que el
     * segundo llamador que aparezca podria crear dinero de la nada. Una
     * invariante que vive fuera de la operacion atomica no es una invariante.
     */
    public Optional<ResultadoDebito> debitar(Long cuentaId, BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto a debitar debe ser mayor que cero: " + monto);
        }
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

    /**
     * Aplica un abono. Devuelve vacio si la cuenta no existe.
     *
     * Misma estructura que {@link #debitar(Long, BigDecimal)} y por la misma
     * razon: leer el saldo, sumar y escribir tienen que ser una sola operacion
     * atomica sobre la clave, o dos abonos simultaneos sobre la misma cuenta
     * pueden partir del mismo saldo y perderse uno.
     *
     * Un abono no puede fallar por reglas de saldo: sumar nunca deja la cuenta
     * en negativo. Lo unico que se valida es que el monto sea positivo, y se
     * valida aqui dentro por lo mismo que en el debito: un monto negativo en un
     * {@code add} es un retiro sin autorizacion, y una invariante que vive fuera
     * de la operacion atomica no es una invariante.
     */
    public Optional<BigDecimal> abonar(Long cuentaId, BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto a abonar debe ser mayor que cero: " + monto);
        }
        CuentaRegistro actualizado = cuentas.computeIfPresent(cuentaId,
                (id, registro) -> registro.conSaldo(registro.saldo().add(monto)));
        return Optional.ofNullable(actualizado).map(CuentaRegistro::saldo);
    }

    public int cantidadCuentas() {
        return cuentas.size();
    }

    public record ResultadoDebito(boolean aprobado, String motivoRechazo, BigDecimal saldoResultante) {
    }
}
