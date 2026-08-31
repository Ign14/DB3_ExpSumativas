package cl.duoc.bancoxyz.batch.common.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;

import java.time.Duration;

/**
 * Imprime en consola un resumen de la ejecución del Job (estado, duración y
 * contadores de lectura/escritura/omisión por Step). Es la evidencia de
 * ejecución que se adjunta como captura de pantalla / log en la entrega,
 * y también se usa para comparar tiempos entre distintas configuraciones
 * de particionado (ver README, sección "Comparación de configuraciones").
 */
@Slf4j
public class JobMetricsListener implements JobExecutionListener {

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("==================================================================");
        log.info(">> INICIO job [{}] parametros={}", jobExecution.getJobInstance().getJobName(),
                jobExecution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Duration duracion = Duration.between(jobExecution.getStartTime(), jobExecution.getEndTime());
        log.info("<< FIN job [{}] estado={} duracion={} ms",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(), duracion.toMillis());

        for (StepExecution step : jobExecution.getStepExecutions()) {
            log.info("   - step [{}] leidos={} escritos={} omitidos(lectura/proceso/escritura)={}/{}/{} estado={}",
                    step.getStepName(), step.getReadCount(), step.getWriteCount(),
                    step.getReadSkipCount(), step.getProcessSkipCount(), step.getWriteSkipCount(),
                    step.getStatus());
        }
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            log.info(">> RESULTADO: job [{}] finalizado exitosamente.", jobExecution.getJobInstance().getJobName());
        } else {
            log.error(">> RESULTADO: job [{}] finalizado con estado {}.", jobExecution.getJobInstance().getJobName(),
                    jobExecution.getStatus());
        }
        log.info("==================================================================");
    }
}
