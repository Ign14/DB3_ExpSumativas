package cl.duoc.bancoxyz.cuentas.web;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.RetiroRequest;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.cuentas.domain.CuentaRepositoryEnMemoria;
import cl.duoc.bancoxyz.cuentas.service.LiquidacionService;
import cl.duoc.bancoxyz.cuentas.service.RetiroService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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
    private final LiquidacionService liquidacionService;

    public CuentaController(CuentaRepositoryEnMemoria repositorio,
                            RetiroService retiroService,
                            LiquidacionService liquidacionService) {
        this.repositorio = repositorio;
        this.retiroService = retiroService;
        this.liquidacionService = liquidacionService;
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

    /**
     * Abona un monto a la cuenta. Es la primitiva que usa pagos-service para
     * liquidar un deposito o la punta de destino de una transferencia; no la
     * consume ningun canal directamente.
     */
    @PostMapping("/{cuentaId}/abono")
    public ResultadoLiquidacion abonar(@PathVariable Long cuentaId,
                                       @RequestBody MontoRequest solicitud) {
        BigDecimal saldo = liquidacionService.abonar(cuentaId, monto(solicitud));
        return new ResultadoLiquidacion(cuentaId, true, saldo, null);
    }

    /**
     * Carga un monto a la cuenta si hay fondos.
     *
     * Un cargo sin fondos responde 200 con {@code aplicado: false} y no 4xx, por
     * lo mismo que un retiro rechazado: la peticion estaba bien formada y el
     * servicio la evaluo. Quien orquesta la transferencia necesita distinguir
     * "no habia saldo" de "la llamada fallo", y un 4xx haria que su cliente
     * resiliente contara el rechazo como un error de la dependencia.
     */
    @PostMapping("/{cuentaId}/cargo")
    public ResultadoLiquidacion cargar(@PathVariable Long cuentaId,
                                       @RequestBody MontoRequest solicitud) {
        return liquidacionService.cargar(cuentaId, monto(solicitud))
                .map(saldo -> new ResultadoLiquidacion(cuentaId, true, saldo, null))
                .orElseGet(() -> new ResultadoLiquidacion(cuentaId, false, null, "Fondos insuficientes"));
    }

    private static BigDecimal monto(MontoRequest solicitud) {
        if (solicitud == null) {
            throw new ParametroInvalidoException("Debe indicar el monto.");
        }
        return solicitud.monto();
    }

    public record MontoRequest(BigDecimal monto) {
    }

    public record ResultadoLiquidacion(Long cuentaId, boolean aplicado,
                                       BigDecimal saldoResultante, String motivoRechazo) {
    }
}
