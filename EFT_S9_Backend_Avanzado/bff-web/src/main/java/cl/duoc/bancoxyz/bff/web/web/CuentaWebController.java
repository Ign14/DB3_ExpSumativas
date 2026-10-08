package cl.duoc.bancoxyz.bff.web.web;

import cl.duoc.bancoxyz.common.dto.ClienteDTO;
import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.OperacionResponse;
import cl.duoc.bancoxyz.bff.common.exception.ParametroInvalidoException;
import cl.duoc.bancoxyz.bff.web.dto.CuentaWebResponse;
import cl.duoc.bancoxyz.bff.web.service.CuentaWebService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/web")
public class CuentaWebController {

    private final CuentaWebService servicio;

    public CuentaWebController(CuentaWebService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/cuentas/{cuentaId}")
    public CuentaWebResponse obtenerCuentaCompleta(@PathVariable Long cuentaId) {
        return servicio.obtenerCuentaCompleta(cuentaId);
    }

    @GetMapping("/cuentas")
    public List<CuentaDTO> listarCuentas() {
        return servicio.listarCuentas();
    }

    @PostMapping("/cuentas/{cuentaId}/deposito")
    public OperacionResponse depositar(@PathVariable Long cuentaId,
                                           @RequestBody MontoRequest solicitud) {
        return servicio.depositar(cuentaId, montoValido(solicitud), descripcion(solicitud));
    }

    @PostMapping("/cuentas/{cuentaOrigen}/transferencia")
    public OperacionResponse transferir(@PathVariable Long cuentaOrigen,
                                            @RequestBody TransferenciaWebRequest solicitud) {
        if (solicitud == null || solicitud.cuentaDestino() == null) {
            throw new ParametroInvalidoException("Debe indicar la cuenta de destino.");
        }
        if (solicitud.monto() == null || solicitud.monto().signum() <= 0) {
            throw new ParametroInvalidoException("El monto debe ser mayor que cero.");
        }
        return servicio.transferir(cuentaOrigen, solicitud.cuentaDestino(),
                solicitud.monto(), solicitud.descripcion());
    }

    @PutMapping("/clientes/{clienteId}/nombre")
    public ClienteDTO actualizarNombre(@PathVariable Long clienteId,
                                             @RequestBody CambioNombreRequest solicitud) {
        if (solicitud == null || solicitud.nombre() == null || solicitud.nombre().isBlank()) {
            throw new ParametroInvalidoException("Debe indicar el nombre del titular.");
        }
        return servicio.actualizarNombreTitular(clienteId, solicitud.nombre().trim());
    }

    /**
     * La validacion del monto se repite aqui aunque pagos-service tambien la
     * haga. No es desconfianza del microservicio: es evitar una llamada remota
     * para algo que se resuelve mirando el cuerpo de la peticion, y poder
     * devolver un mensaje de error en el idioma del canal.
     */
    private static BigDecimal montoValido(MontoRequest solicitud) {
        if (solicitud == null || solicitud.monto() == null || solicitud.monto().signum() <= 0) {
            throw new ParametroInvalidoException("El monto debe ser mayor que cero.");
        }
        return solicitud.monto();
    }

    private static String descripcion(MontoRequest solicitud) {
        return solicitud == null ? null : solicitud.descripcion();
    }

    public record MontoRequest(BigDecimal monto, String descripcion) {
    }

    public record TransferenciaWebRequest(Long cuentaDestino, BigDecimal monto, String descripcion) {
    }

    public record CambioNombreRequest(String nombre) {
    }
}
