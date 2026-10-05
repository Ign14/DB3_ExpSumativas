package cl.duoc.bancoxyz.cuentas.web;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.RetiroRequest;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.cuentas.domain.CuentaRepositoryEnMemoria;
import cl.duoc.bancoxyz.cuentas.service.RetiroService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API del dominio de cuentas.
 *
 * Las rutas no llevan el prefijo /api: ese prefijo lo pone el gateway al
 * enrutar, de modo que el microservicio no tenga que saber bajo que camino
 * publico queda expuesto.
 */
@RestController
@RequestMapping("/cuentas")
public class CuentaController {

    private final CuentaRepositoryEnMemoria repositorio;
    private final RetiroService retiroService;

    public CuentaController(CuentaRepositoryEnMemoria repositorio, RetiroService retiroService) {
        this.repositorio = repositorio;
        this.retiroService = retiroService;
    }

    @GetMapping
    public List<CuentaDTO> listar() {
        return repositorio.listar();
    }

    @GetMapping("/{cuentaId}")
    public CuentaDTO obtener(@PathVariable Long cuentaId) {
        return repositorio.buscar(cuentaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("La cuenta " + cuentaId + " no existe."));
    }

    @PostMapping("/{cuentaId}/retiro")
    public ResponseEntity<RetiroResponse> retirar(@PathVariable Long cuentaId,
                                                  @RequestBody RetiroRequest solicitud) {
        RetiroResponse respuesta = retiroService.retirar(cuentaId, solicitud);
        // Un retiro rechazado por reglas de negocio no es un error del cliente:
        // la peticion estaba bien formada y el servicio la evaluo. Se devuelve
        // 200 con el motivo, y se reservan los 4xx para peticiones mal hechas.
        return ResponseEntity.ok(respuesta);
    }
}
