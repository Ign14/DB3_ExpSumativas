package cl.duoc.bancoxyz.batch.common.exception;

/**
 * Excepción de negocio lanzada por los {@code ItemProcessor} cuando un
 * registro del archivo legacy no cumple las reglas mínimas de consistencia
 * (montos negativos o vacíos, fechas no parseables, tipos fuera de dominio,
 * edades no realistas, etc.).
 * <p>
 * Es capturada por la política de skip configurada en cada Step
 * ({@code faultTolerant().skip(RegistroInvalidoException.class)}), lo que
 * permite que el Job continúe procesando el resto del archivo sin
 * detenerse, mientras el {@link cl.duoc.bancoxyz.batch.common.listener.AnomaliaSkipListener}
 * deja constancia del registro descartado.
 */
public class RegistroInvalidoException extends RuntimeException {

    public RegistroInvalidoException(String motivo) {
        super(motivo);
    }
}
