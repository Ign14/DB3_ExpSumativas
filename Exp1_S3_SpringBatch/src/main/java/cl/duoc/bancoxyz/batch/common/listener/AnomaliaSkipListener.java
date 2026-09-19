package cl.duoc.bancoxyz.batch.common.listener;

import cl.duoc.bancoxyz.batch.common.model.RegistroAnomalia;
import cl.duoc.bancoxyz.batch.common.repository.RegistroAnomaliaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.SkipListener;

/**
 * Deja constancia en la tabla {@code registro_anomalia} de cada ítem que la
 * política de skip del Step decide omitir (fallo de lectura, de
 * procesamiento o de escritura), y lo refleja también en el log de consola
 * como evidencia de ejecución. Una misma instancia (parametrizada con el
 * nombre del proceso) se reutiliza como bean para los tres jobs.
 */
@Slf4j
public class AnomaliaSkipListener implements SkipListener<Object, Object> {

    private final String proceso;
    private final RegistroAnomaliaRepository repository;

    public AnomaliaSkipListener(String proceso, RegistroAnomaliaRepository repository) {
        this.proceso = proceso;
        this.repository = repository;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("[{}] Registro omitido en lectura: {}", proceso, t.getMessage());
        repository.save(RegistroAnomalia.de(proceso, "N/D", "ERROR_LECTURA", String.valueOf(t.getMessage())));
    }

    @Override
    public void onSkipInProcess(Object item, Throwable t) {
        log.warn("[{}] Registro omitido en procesamiento: {} -> {}", proceso, item, t.getMessage());
        repository.save(RegistroAnomalia.de(proceso, String.valueOf(item), "VALIDACION_FALLIDA", String.valueOf(t.getMessage())));
    }

    @Override
    public void onSkipInWrite(Object item, Throwable t) {
        log.warn("[{}] Registro omitido en escritura: {} -> {}", proceso, item, t.getMessage());
        repository.save(RegistroAnomalia.de(proceso, String.valueOf(item), "ERROR_ESCRITURA", String.valueOf(t.getMessage())));
    }
}
