package cl.duoc.bancoxyz.clientes.domain;

import cl.duoc.bancoxyz.common.dto.ClienteDTO;
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
 * Almacen en memoria de los clientes, cargado al arrancar desde el mismo
 * archivo legacy que alimenta la migracion batch.
 *
 * Las reglas de validacion son las mismas que aplica el proceso batch y el
 * dominio de cuentas: edad entre 18 y 120 y tipo de producto dentro del dominio
 * conocido. Que las tres capas rechacen lo mismo no es duplicacion por descuido:
 * el batch decide que entra al sistema, y cada servicio decide que puede servir.
 * Si manana el archivo se carga por otra via, este servicio sigue sin publicar
 * un cliente de 150 anos.
 *
 * Una diferencia con cuentas-service: aqui una fila sin saldo no se descarta. El
 * saldo es un dato de referencia comercial para segmentar, no la verdad del
 * dinero, que vive en cuentas-service. Un cliente sin saldo informado sigue
 * siendo un cliente y se le asigna el segmento basico.
 */
@Repository
public class ClienteRepositoryEnMemoria {

    private static final Logger log = LoggerFactory.getLogger(ClienteRepositoryEnMemoria.class);
    private static final Set<String> TIPOS_VALIDOS = Set.of("ahorro", "prestamo", "hipoteca");

    private final Map<Long, ClienteRegistro> clientes = new ConcurrentHashMap<>();
    private final Map<Long, ActividadCliente> actividad = new ConcurrentHashMap<>();

    @PostConstruct
    public void cargarDatos() {
        int leidas = 0;
        int validas = 0;
        int omitidas = 0;
        Resource recurso = new PathMatchingResourcePatternResolver()
                .getResource("classpath:data/intereses_trimestrales.csv");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(recurso.getInputStream(), StandardCharsets.UTF_8))) {
            String linea = reader.readLine(); // encabezado
            while ((linea = reader.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                leidas++;
                Optional<ClienteRegistro> registro = parsear(linea);
                if (registro.isPresent()) {
                    validas++;
                    clientes.put(registro.get().clienteId(), registro.get());
                } else {
                    omitidas++;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar intereses_trimestrales.csv", ex);
        }
        log.info(">> clientes-service: {} filas leidas = {} validas + {} omitidas por datos inconsistentes",
                leidas, validas, omitidas);
        log.info(">> clientes-service: {} clientes distintos cargados", clientes.size());
    }

    private Optional<ClienteRegistro> parsear(String linea) {
        String[] campos = linea.split(",", -1);
        if (campos.length < 5) {
            return Optional.empty();
        }
        try {
            Long clienteId = Long.parseLong(campos[0].trim());
            String nombre = campos[1].trim();
            if (nombre.isBlank() || "Unknown".equalsIgnoreCase(nombre)) {
                nombre = "Sin nombre registrado";
            }
            String saldoTexto = campos[2].trim();
            String edadTexto = campos[3].trim();
            String tipo = campos[4].trim().toLowerCase();

            if (edadTexto.isBlank() || !TIPOS_VALIDOS.contains(tipo)) {
                return Optional.empty();
            }
            int edad = Integer.parseInt(edadTexto);
            if (edad < 18 || edad > 120) {
                return Optional.empty();
            }
            BigDecimal saldo = saldoTexto.isBlank() ? BigDecimal.ZERO : new BigDecimal(saldoTexto);
            if (saldo.signum() < 0) {
                saldo = BigDecimal.ZERO;
            }
            return Optional.of(new ClienteRegistro(clienteId, nombre, edad, tipo, saldo));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public List<ClienteDTO> listar(int limite) {
        return clientes.values().stream()
                .sorted((a, b) -> Long.compare(a.clienteId(), b.clienteId()))
                .limit(limite)
                .map(this::aDto)
                .toList();
    }

    public Optional<ClienteDTO> buscar(Long clienteId) {
        return Optional.ofNullable(clientes.get(clienteId)).map(this::aDto);
    }

    /** Actualiza el nombre del titular. Vacio si el cliente no existe. */
    public Optional<ClienteDTO> renombrar(Long clienteId, String nuevoNombre) {
        ClienteRegistro actualizado = clientes.computeIfPresent(clienteId,
                (id, registro) -> registro.conNombre(nuevoNombre));
        return Optional.ofNullable(actualizado).map(this::aDto);
    }

    /**
     * Registra una operacion aprobada del cliente.
     *
     * Se usa {@code compute} y no un leer-modificar-escribir porque los eventos
     * de un topico con varias particiones llegan en hilos distintos: dos
     * operaciones del mismo cliente procesadas a la vez perderian una cuenta si
     * la lectura y la escritura no fueran una sola operacion atomica.
     *
     * No se comprueba que el cliente exista. Un evento habla de algo que ya
     * paso, y descartarlo porque la cuenta no esta en este archivo solo
     * perderia informacion: el cliente puede haberse creado despues de la carga.
     */
    public void registrarOperacion(Long clienteId, String descripcion) {
        actividad.compute(clienteId, (id, actual) ->
                (actual == null ? ActividadCliente.VACIA : actual).conOperacion(descripcion));
    }

    public void registrarAlerta(Long clienteId) {
        actividad.compute(clienteId, (id, actual) ->
                (actual == null ? ActividadCliente.VACIA : actual).conAlerta());
    }

    public ActividadCliente actividadDe(Long clienteId) {
        return actividad.getOrDefault(clienteId, ActividadCliente.VACIA);
    }

    public int cantidadClientes() {
        return clientes.size();
    }

    private ClienteDTO aDto(ClienteRegistro registro) {
        ActividadCliente act = actividadDe(registro.clienteId());
        return new ClienteDTO(
                registro.clienteId(),
                registro.nombre(),
                registro.segmento(),
                registro.edad(),
                registro.tipoCuentaPrincipal(),
                registro.saldoReferencial(),
                act.operaciones(),
                act.alertas(),
                act.ultimaOperacion());
    }
}
