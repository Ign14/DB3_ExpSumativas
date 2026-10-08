package cl.duoc.bancoxyz.clientes.web;

import cl.duoc.bancoxyz.clientes.domain.ClienteRepositoryEnMemoria;
import cl.duoc.bancoxyz.common.dto.ClienteDTO;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API del dominio de clientes.
 *
 * El listado esta paginado por un limite y no devuelve el padron completo. Son
 * cientos de clientes y ninguna pantalla los muestra todos; un endpoint que
 * devuelve todo por defecto es el que termina trayendo un payload de megas a un
 * telefono.
 */
@RestController
@RequestMapping("/clientes")
public class ClienteController {

    private static final int LIMITE_MAXIMO = 200;

    private final ClienteRepositoryEnMemoria repositorio;

    public ClienteController(ClienteRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping
    public List<ClienteDTO> listar(@RequestParam(defaultValue = "20") int limite) {
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new ParametroInvalidoException(
                    "El limite debe estar entre 1 y " + LIMITE_MAXIMO + ": " + limite);
        }
        return repositorio.listar(limite);
    }

    @GetMapping("/{clienteId}")
    public ClienteDTO obtener(@PathVariable Long clienteId) {
        return repositorio.buscar(clienteId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cliente " + clienteId + " no existe."));
    }

    /**
     * Actualiza el nombre del titular, que es la operacion de mantenimiento de
     * datos personales que pide el caso.
     *
     * Es PUT y no PATCH porque reemplaza por completo el unico campo editable
     * del recurso, y es idempotente: repetir la misma peticion deja el mismo
     * estado.
     */
    @PutMapping("/{clienteId}/nombre")
    public ClienteDTO renombrar(@PathVariable Long clienteId,
                                @RequestBody CambioNombreRequest solicitud) {
        if (solicitud == null || solicitud.nombre() == null || solicitud.nombre().isBlank()) {
            throw new ParametroInvalidoException("Debe indicar el nombre del titular.");
        }
        String nombre = solicitud.nombre().trim();
        if (nombre.length() > 120) {
            throw new ParametroInvalidoException("El nombre no puede exceder 120 caracteres.");
        }
        return repositorio.renombrar(clienteId, nombre)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cliente " + clienteId + " no existe."));
    }

    public record CambioNombreRequest(String nombre) {
    }
}
