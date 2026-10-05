package cl.duoc.bancoxyz.movimientos.service;

import cl.duoc.bancoxyz.common.dto.FichaCuentaDTO;
import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import cl.duoc.bancoxyz.movimientos.cliente.CuentasClienteResiliente;
import cl.duoc.bancoxyz.movimientos.cliente.ResultadoCuenta;
import cl.duoc.bancoxyz.movimientos.domain.MovimientoRepositoryEnMemoria;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Agrega en una sola respuesta el historial propio y los datos de cuenta, que
 * son de otro microservicio.
 *
 * Es el punto donde se decide que significa "no hay datos". Si cuentas-service
 * dice que la cuenta no existe y aqui tampoco hay historial, la cuenta no
 * existe y corresponde un 404. Si cuentas-service no respondio, no se puede
 * concluir eso: se devuelve lo que si tenemos, marcado como degradado.
 */
@Service
public class FichaCuentaService {

    private final CuentasClienteResiliente clienteCuentas;
    private final MovimientoRepositoryEnMemoria repositorio;

    public FichaCuentaService(CuentasClienteResiliente clienteCuentas,
                              MovimientoRepositoryEnMemoria repositorio) {
        this.clienteCuentas = clienteCuentas;
        this.repositorio = repositorio;
    }

    public FichaCuentaDTO obtener(Long cuentaId) {
        ResultadoCuenta resultado = consultarCuenta(cuentaId);

        // Una sola lectura del historial, y el resumen calculado sobre esa misma
        // lista. Leer dos veces el repositorio permitiria que un evento de la
        // cola entrara entremedio y la respuesta saliera incoherente consigo
        // misma: un resumen con un movimiento mas de los que trae el detalle.
        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(cuentaId);

        if (resultado.origen() == ResultadoCuenta.Origen.NO_ENCONTRADA && movimientos.isEmpty()) {
            throw new RecursoNoEncontradoException("La cuenta " + cuentaId + " no existe.");
        }

        return new FichaCuentaDTO(
                cuentaId,
                resultado.cuenta(),
                resultado.origen().name(),
                repositorio.resumir(cuentaId, movimientos),
                movimientos);
    }

    /**
     * El fallback de Resilience4j cubre cualquier excepcion, asi que en
     * condiciones normales este {@code join} no lanza. Pero si el fallback no
     * llegara a aplicarse —aspectos no activos, una llamada que no pase por el
     * proxy—, {@code join} envuelve la causa en una CompletionException, que
     * ningun manejador reconoce y terminaria en un 500 genérico. Se desenvuelve
     * aqui para que el error diga lo que de verdad paso.
     */
    private ResultadoCuenta consultarCuenta(Long cuentaId) {
        try {
            return clienteCuentas.obtenerCuenta(cuentaId).join();
        } catch (CompletionException ex) {
            Throwable causa = ex.getCause() == null ? ex : ex.getCause();
            if (causa instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ServicioNoDisponibleException(
                    "No se pudo obtener la cuenta " + cuentaId + " de cuentas-service", causa);
        }
    }
}
