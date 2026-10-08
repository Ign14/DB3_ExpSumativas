package cl.duoc.bancoxyz.bff.web.service;

import cl.duoc.bancoxyz.bff.common.client.ClientesApiClient;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.client.PagosApiClient;
import cl.duoc.bancoxyz.common.dto.ClienteDTO;
import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.dto.OperacionResponse;
import cl.duoc.bancoxyz.common.dto.ResumenMovimientosDTO;
import cl.duoc.bancoxyz.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import cl.duoc.bancoxyz.bff.web.dto.CuentaWebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/** Agregacion de los microservicios para el canal web. */
@Service
public class CuentaWebService {

    private static final Logger log = LoggerFactory.getLogger(CuentaWebService.class);
    private static final String CANAL = "web";

    private final CuentasApiClient cuentasApiClient;
    private final MovimientosApiClient movimientosApiClient;
    private final ClientesApiClient clientesApiClient;
    private final PagosApiClient pagosApiClient;

    public CuentaWebService(CuentasApiClient cuentasApiClient,
                            MovimientosApiClient movimientosApiClient,
                            ClientesApiClient clientesApiClient,
                            PagosApiClient pagosApiClient) {
        this.cuentasApiClient = cuentasApiClient;
        this.movimientosApiClient = movimientosApiClient;
        this.clientesApiClient = clientesApiClient;
        this.pagosApiClient = pagosApiClient;
    }

    /**
     * Una sola peticion del cliente web se traduce en cuatro llamadas a tres
     * microservicios. Esa agregacion es el trabajo que el patron BFF le quita al
     * frontend: sin el, el navegador tendria que hacer las cuatro, conocer la
     * forma de las tres APIs y manejar por su cuenta que una de ellas falle.
     */
    public CuentaWebResponse obtenerCuentaCompleta(Long cuentaId) {
        CuentaDTO cuenta = cuentasApiClient.obtenerCuenta(cuentaId);
        List<MovimientoDTO> historial = movimientosApiClient.obtenerMovimientos(cuentaId);
        ResumenMovimientosDTO resumen = movimientosApiClient.obtenerResumen(cuentaId);
        return new CuentaWebResponse(cuenta, perfilOpcional(cuentaId), historial, resumen);
    }

    /**
     * El perfil es informacion de contexto, no el dato que el operador vino a
     * buscar. Si clientes-service no responde se devuelve nulo y se sigue: el
     * saldo y el historial son lo que sostiene la pantalla.
     *
     * Una cuenta sin perfil tampoco es un error: el padron de clientes y el de
     * cuentas salen del mismo archivo legacy pero con reglas de validacion
     * distintas, asi que hay cuentas validas cuyo titular quedo fuera por edad
     * o por tipo de producto.
     */
    private ClienteDTO perfilOpcional(Long cuentaId) {
        try {
            return clientesApiClient.obtenerPerfil(cuentaId);
        } catch (CuentaNoEncontradaException ex) {
            return null;
        } catch (ServicioCoreNoDisponibleException ex) {
            log.warn("Cuenta {} servida sin perfil: clientes-service no respondio ({})",
                    cuentaId, ex.getMessage());
            return null;
        }
    }

    public List<CuentaDTO> listarCuentas() {
        return cuentasApiClient.listarCuentas();
    }

    public TransaccionDiariaResumenDTO obtenerResumenDiario() {
        return movimientosApiClient.obtenerResumenTransaccionesDiarias();
    }

    public ClienteDTO actualizarNombreTitular(Long clienteId, String nombre) {
        return clientesApiClient.actualizarNombre(clienteId, nombre);
    }

    public OperacionResponse depositar(Long cuentaId, BigDecimal monto, String descripcion) {
        return pagosApiClient.depositar(cuentaId, monto, CANAL, descripcion);
    }

    public OperacionResponse transferir(Long cuentaOrigen, Long cuentaDestino,
                                            BigDecimal monto, String descripcion) {
        return pagosApiClient.transferir(cuentaOrigen, cuentaDestino, monto, CANAL, descripcion);
    }
}
