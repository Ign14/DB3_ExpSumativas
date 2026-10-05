package cl.duoc.bancoxyz.cuentas.service;

import cl.duoc.bancoxyz.common.dto.RetiroRequest;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import cl.duoc.bancoxyz.common.evento.RetiroRealizadoEvento;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.cuentas.domain.CuentaRepositoryEnMemoria;
import cl.duoc.bancoxyz.cuentas.mensajeria.PublicadorRetiros;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Reglas del retiro, separadas del controlador para poder probarlas sin
 * levantar el contexto web ni el broker.
 *
 * Hay dos niveles de rechazo y conviene no confundirlos:
 *
 * - El limite por operacion es una regla configurable del negocio. Se puede
 *   cambiar en el Config Server sin recompilar ni volver a desplegar.
 * - El saldo que no puede quedar negativo es una invariante del dominio. Vive
 *   en el repositorio, dentro de la misma operacion atomica que aplica el
 *   debito, porque ninguna configuracion deberia poder desactivarla.
 */
@Service
public class RetiroService {

    private final CuentaRepositoryEnMemoria repositorio;
    private final PublicadorRetiros publicador;
    private final BigDecimal limitePorOperacion;

    public RetiroService(CuentaRepositoryEnMemoria repositorio,
                         PublicadorRetiros publicador,
                         @Value("${banco.cuentas.limite-retiro-por-operacion}") BigDecimal limitePorOperacion) {
        this.repositorio = repositorio;
        this.publicador = publicador;
        this.limitePorOperacion = limitePorOperacion;
    }

    public RetiroResponse retirar(Long cuentaId, RetiroRequest solicitud) {
        if (solicitud == null || solicitud.monto() == null) {
            throw new ParametroInvalidoException("Debe indicar el monto a retirar.");
        }
        BigDecimal monto = solicitud.monto();
        if (monto.signum() <= 0) {
            throw new ParametroInvalidoException("El monto debe ser mayor que cero.");
        }

        String canal = (solicitud.canal() == null || solicitud.canal().isBlank())
                ? "no-informado"
                : solicitud.canal().trim();

        if (monto.compareTo(limitePorOperacion) > 0) {
            return new RetiroResponse(cuentaId, monto, null, false,
                    "El monto supera el limite de " + limitePorOperacion.toPlainString() + " por operacion.",
                    null, false);
        }

        Optional<CuentaRepositoryEnMemoria.ResultadoDebito> resultado = repositorio.debitar(cuentaId, monto);
        if (resultado.isEmpty()) {
            throw new RecursoNoEncontradoException("La cuenta " + cuentaId + " no existe.");
        }

        CuentaRepositoryEnMemoria.ResultadoDebito debito = resultado.get();
        if (!debito.aprobado()) {
            return new RetiroResponse(cuentaId, monto, debito.saldoResultante(), false,
                    debito.motivoRechazo(), null, false);
        }

        String eventoId = UUID.randomUUID().toString();
        RetiroRealizadoEvento evento = new RetiroRealizadoEvento(
                eventoId, cuentaId, monto, debito.saldoResultante(),
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE), canal);
        boolean publicado = publicador.publicar(evento);

        return new RetiroResponse(cuentaId, monto, debito.saldoResultante(), true, null, eventoId, publicado);
    }
}
