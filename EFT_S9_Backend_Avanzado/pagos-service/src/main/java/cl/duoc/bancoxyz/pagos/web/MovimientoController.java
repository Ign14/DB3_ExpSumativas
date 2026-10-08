package cl.duoc.bancoxyz.pagos.web;

import cl.duoc.bancoxyz.common.dto.FichaCuentaDTO;
import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.pagos.domain.MovimientoRepositoryEnMemoria;
import cl.duoc.bancoxyz.pagos.service.FichaCuentaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API del dominio de movimientos. */
@RestController
@RequestMapping("/movimientos")
public class MovimientoController {

    private static final int LIMITE_MAXIMO = 20;

    private final MovimientoRepositoryEnMemoria repositorio;
    private final FichaCuentaService fichaCuentaService;

    public MovimientoController(MovimientoRepositoryEnMemoria repositorio,
                                FichaCuentaService fichaCuentaService) {
        this.repositorio = repositorio;
        this.fichaCuentaService = fichaCuentaService;
    }

    /**
     * Historial de una cuenta. Con {@code limite} devuelve los mas recientes
     * primero; sin el, el historial completo en orden cronologico.
     */
    @GetMapping("/{cuentaId}")
    public List<MovimientoDTO> historial(@PathVariable Long cuentaId,
                                         @RequestParam(required = false) Integer limite) {
        if (!repositorio.existeHistorial(cuentaId)) {
            throw new RecursoNoEncontradoException("La cuenta " + cuentaId + " no tiene movimientos registrados.");
        }
        if (limite == null) {
            return repositorio.obtenerMovimientos(cuentaId);
        }
        // Sin esta validacion, un limite negativo llega hasta Stream.limit, que
        // lanza y se convierte en un 500 por un error que es del cliente.
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new ParametroInvalidoException(
                    "El parametro 'limite' debe estar entre 1 y " + LIMITE_MAXIMO + ".");
        }
        return repositorio.obtenerUltimos(cuentaId, limite);
    }

    @GetMapping("/{cuentaId}/resumen")
    public ResumenMovimientosDTO resumen(@PathVariable Long cuentaId) {
        if (!repositorio.existeHistorial(cuentaId)) {
            throw new RecursoNoEncontradoException("La cuenta " + cuentaId + " no tiene movimientos registrados.");
        }
        return repositorio.resumir(cuentaId);
    }

    /**
     * Vista agregada: datos de la cuenta (de cuentas-service, con tolerancia a
     * fallos) mas historial y totales propios.
     */
    @GetMapping("/{cuentaId}/ficha")
    public FichaCuentaDTO ficha(@PathVariable Long cuentaId) {
        return fichaCuentaService.obtener(cuentaId);
    }

    @PostMapping
    public ResponseEntity<MovimientoDTO> registrar(@RequestBody MovimientoDTO movimiento) {
        return repositorio.registrar(movimiento)
                .map(registrado -> ResponseEntity.status(HttpStatus.CREATED).body(registrado))
                .orElseThrow(() -> new ParametroInvalidoException(
                        "El movimiento no cumple las reglas del dominio: fecha interpretable, "
                                + "monto mayor que cero y tipo entre compra, deposito, pago o retiro."));
    }
}
