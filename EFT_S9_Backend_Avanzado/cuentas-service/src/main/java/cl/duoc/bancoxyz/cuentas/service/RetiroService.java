package cl.duoc.bancoxyz.cuentas.service;

import cl.duoc.bancoxyz.common.dto.RetiroRequest;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.RetiroRealizadoEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.cuentas.domain.CuentaRepositoryEnMemoria;
import cl.duoc.bancoxyz.cuentas.mensajeria.PublicadorEventosKafka;
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
 * levantar el contexto web ni los brokers.
 *
 * Hay dos niveles de rechazo y conviene no confundirlos:
 *
 * - El limite por operacion es una regla configurable del negocio. Se puede
 *   cambiar en el Config Server sin recompilar ni volver a desplegar.
 * - El saldo que no puede quedar negativo es una invariante del dominio. Vive
 *   en el repositorio, dentro de la misma operacion atomica que aplica el
 *   debito, porque ninguna configuracion deberia poder desactivarla.
 *
 * Un retiro aprobado produce dos mensajes y no uno, por dos brokers distintos.
 * No es redundancia:
 *
 * - A la cola JMS va una instruccion con un destinatario: pagos-service tiene
 *   que anotar este movimiento en el historial, exactamente una vez, y si esta
 *   caido el mensaje debe esperarlo.
 * - Al topico de Kafka va un hecho sin destinatario: "este retiro ocurrio". Hoy
 *   lo lee clientes-service; manana lo pueden leer analitica o notificaciones
 *   sin que este servicio cambie.
 *
 * Mandar los dos por el mismo canal obligaria a elegir: con una cola, el
 * segundo consumidor que apareciera le robaria mensajes al primero; con un
 * topico, habria que inventar la garantia de procesamiento unico que la cola ya
 * da.
 */
@Service
public class RetiroService {

    private final CuentaRepositoryEnMemoria repositorio;
    private final PublicadorRetiros publicadorCola;
    private final PublicadorEventosKafka publicadorTopicos;
    private final BigDecimal limitePorOperacion;

    public RetiroService(CuentaRepositoryEnMemoria repositorio,
                         PublicadorRetiros publicadorCola,
                         PublicadorEventosKafka publicadorTopicos,
                         @Value("${banco.cuentas.limite-retiro-por-operacion}") BigDecimal limitePorOperacion) {
        this.repositorio = repositorio;
        this.publicadorCola = publicadorCola;
        this.publicadorTopicos = publicadorTopicos;
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
        String hoy = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        if (monto.compareTo(limitePorOperacion) > 0) {
            // Un intento por sobre el limite es informacion de seguridad, no
            // solo un rechazo: visto una vez es un error del titular, visto
            // veinte veces en un minuto es otra cosa. Por eso sale al topico.
            publicadorTopicos.publicarAlerta(new AlertaSeguridadEvento(
                    UUID.randomUUID().toString(),
                    AlertaSeguridadEvento.RETIRO_SOBRE_LIMITE,
                    cuentaId,
                    "Intento de retiro por " + monto.toPlainString()
                            + " sobre el limite de " + limitePorOperacion.toPlainString()
                            + " desde el canal " + canal,
                    AlertaSeguridadEvento.SEVERIDAD_MEDIA,
                    hoy));
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
            publicadorTopicos.publicarAlerta(new AlertaSeguridadEvento(
                    UUID.randomUUID().toString(),
                    AlertaSeguridadEvento.FONDOS_INSUFICIENTES,
                    cuentaId,
                    "Retiro por " + monto.toPlainString() + " rechazado con saldo "
                            + debito.saldoResultante().toPlainString() + " desde el canal " + canal,
                    AlertaSeguridadEvento.SEVERIDAD_INFO,
                    hoy));
            return new RetiroResponse(cuentaId, monto, debito.saldoResultante(), false,
                    debito.motivoRechazo(), null, false);
        }

        String eventoId = UUID.randomUUID().toString();
        boolean publicado = publicadorCola.publicar(new RetiroRealizadoEvento(
                eventoId, cuentaId, monto, debito.saldoResultante(), hoy, canal));

        publicadorTopicos.publicarTransaccion(new TransaccionCompletadaEvento(
                eventoId,
                TransaccionCompletadaEvento.RETIRO,
                cuentaId,
                null,
                monto,
                debito.saldoResultante(),
                hoy,
                canal));

        // El comprobante informa si el evento de la cola salio, que es el que
        // determina si el movimiento llegara al historial. El del topico no se
        // informa: ningun consumidor de ese topico afecta lo que el cliente
        // acaba de hacer, y meterlo en el comprobante solo daria al cliente un
        // dato que no puede usar para nada.
        return new RetiroResponse(cuentaId, monto, debito.saldoResultante(), true, null, eventoId, publicado);
    }
}
