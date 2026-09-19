package cl.duoc.bancoxyz.batch.transacciones;

import cl.duoc.bancoxyz.batch.common.repository.RegistroAnomaliaRepository;
import cl.duoc.bancoxyz.batch.transacciones.model.ResumenTransaccionesDiarias;
import cl.duoc.bancoxyz.batch.transacciones.repository.ResumenTransaccionesDiariasRepository;
import cl.duoc.bancoxyz.batch.transacciones.repository.TransaccionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.LocalDateTime;

/**
 * Segundo Step del job de transacciones diarias: agrega los resultados del
 * step particionado en un único resumen de auditoría (total procesado,
 * créditos, débitos, anomalías y monto neto del día), cumpliendo el
 * requerimiento "Reporte de Transacciones Diarias: procesar transacciones
 * diarias para detectar anomalías y generar un resumen".
 */
@Slf4j
@RequiredArgsConstructor
public class ResumenTransaccionesTasklet implements Tasklet {

    private static final String PROCESO = "transacciones-diarias";

    private final TransaccionRepository transaccionRepository;
    private final RegistroAnomaliaRepository anomaliaRepository;
    private final ResumenTransaccionesDiariasRepository resumenRepository;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        long totalCreditos = transaccionRepository.countByTipo("credito");
        long totalDebitos = transaccionRepository.countByTipo("debito");
        long totalAnomalias = anomaliaRepository.countByProceso(PROCESO);

        ResumenTransaccionesDiarias resumen = new ResumenTransaccionesDiarias(
                null,
                LocalDateTime.now(),
                totalCreditos + totalDebitos,
                totalCreditos,
                totalDebitos,
                totalAnomalias,
                transaccionRepository.calcularMontoNeto());

        resumenRepository.save(resumen);

        log.info("RESUMEN transacciones diarias -> procesadas={} creditos={} debitos={} anomalias={} montoNeto={}",
                resumen.getTotalProcesadas(), resumen.getTotalCreditos(), resumen.getTotalDebitos(),
                resumen.getTotalAnomalias(), resumen.getMontoNeto());

        return RepeatStatus.FINISHED;
    }
}
