package cl.duoc.bancoxyz.batch.transacciones;

import cl.duoc.bancoxyz.batch.common.listener.AnomaliaSkipListener;
import cl.duoc.bancoxyz.batch.common.listener.JobMetricsListener;
import cl.duoc.bancoxyz.batch.common.partition.RangoLineasFlatFileItemReader;
import cl.duoc.bancoxyz.batch.common.partition.RangoLineasPartitioner;
import cl.duoc.bancoxyz.batch.common.repository.RegistroAnomaliaRepository;
import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.config.BatchInfraConfig;
import cl.duoc.bancoxyz.batch.transacciones.model.Transaccion;
import cl.duoc.bancoxyz.batch.transacciones.model.TransaccionCsv;
import cl.duoc.bancoxyz.batch.transacciones.repository.ResumenTransaccionesDiariasRepository;
import cl.duoc.bancoxyz.batch.transacciones.repository.TransaccionRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Job 1 — "Reporte de Transacciones Diarias".
 * <p>
 * Arquitectura: un {@code partitionStep} (maestro) divide el archivo
 * {@code transacciones.csv} en {@code batch.partition.grid-size} rangos de
 * filas y los procesa en paralelo mediante un
 * {@link TaskExecutorPartitionHandler}; cada partición ejecuta el mismo
 * {@code workerStep} (lectura -> validación/transformación -> escritura en
 * base de datos), con tolerancia a fallos (skip + retry). Un segundo Step
 * (Tasklet) agrega el resumen final.
 */
@Configuration
public class TransaccionesJobConfig {

    private static final String PROCESO = "transacciones-diarias";

    @Bean
    public Job reporteTransaccionesDiariasJob(JobRepository jobRepository,
                                               Step transaccionesPartitionStep,
                                               Step resumenTransaccionesStep) {
        return new JobBuilder("reporteTransaccionesDiariasJob", jobRepository)
                .listener(new JobMetricsListener())
                .start(transaccionesPartitionStep)
                .next(resumenTransaccionesStep)
                .build();
    }

    @Bean
    public Step transaccionesPartitionStep(JobRepository jobRepository,
                                            Step transaccionWorkerStep,
                                            Partitioner transaccionesPartitioner,
                                            TaskExecutor partitionTaskExecutor,
                                            BatchInfraConfig.PartitionProperties partitionProperties) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(partitionTaskExecutor);
        handler.setStep(transaccionWorkerStep);
        handler.setGridSize(partitionProperties.getGridSize());

        return new StepBuilder("transaccionesPartitionStep", jobRepository)
                .partitioner("transaccionWorkerStep", transaccionesPartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    public Partitioner transaccionesPartitioner(@Value("${batch.input.transacciones-path}") Resource resource) {
        return new RangoLineasPartitioner(resource);
    }

    @Bean
    public Step transaccionWorkerStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       ItemReader<TransaccionCsv> transaccionItemReader,
                                       TransaccionItemProcessor transaccionItemProcessor,
                                       JpaItemWriter<Transaccion> transaccionItemWriter,
                                       AnomaliaSkipListener transaccionSkipListener,
                                       SimpleRetryPolicy retryPolicy,
                                       BackOffPolicy backOffPolicy,
                                       @Value("${batch.chunk-size:50}") int chunkSize,
                                       BatchInfraConfig.FaultToleranceProperties faultToleranceProperties) {
        return new StepBuilder("transaccionWorkerStep", jobRepository)
                .<TransaccionCsv, Transaccion>chunk(chunkSize, transactionManager)
                .reader(transaccionItemReader)
                .processor(transaccionItemProcessor)
                .writer(transaccionItemWriter)
                .faultTolerant()
                .skip(RegistroInvalidoException.class)
                .skipLimit(faultToleranceProperties.getSkipLimit())
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .listener(transaccionSkipListener)
                .build();
    }

    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    public org.springframework.batch.item.file.FlatFileItemReader<TransaccionCsv> transaccionItemReader(
            @Value("#{stepExecutionContext['startLine']}") Integer startLine,
            @Value("#{stepExecutionContext['endLine']}") Integer endLine,
            @Value("${batch.input.transacciones-path}") Resource resource) {

        RangoLineasFlatFileItemReader<TransaccionCsv> reader =
                new RangoLineasFlatFileItemReader<>(startLine, endLine, 1);
        reader.setResource(resource);
        reader.setName("transaccionItemReader");

        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("id", "fecha", "monto", "tipo");

        BeanWrapperFieldSetMapper<TransaccionCsv> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(TransaccionCsv.class);

        DefaultLineMapper<TransaccionCsv> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);
        reader.setLineMapper(lineMapper);

        return reader;
    }

    @Bean
    public TransaccionItemProcessor transaccionItemProcessor() {
        return new TransaccionItemProcessor();
    }

    @Bean
    public JpaItemWriter<Transaccion> transaccionItemWriter(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<Transaccion>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(true)
                .build();
    }

    @Bean
    public AnomaliaSkipListener transaccionSkipListener(RegistroAnomaliaRepository repository) {
        return new AnomaliaSkipListener(PROCESO, repository);
    }

    @Bean
    public Step resumenTransaccionesStep(JobRepository jobRepository,
                                          PlatformTransactionManager transactionManager,
                                          TransaccionRepository transaccionRepository,
                                          RegistroAnomaliaRepository anomaliaRepository,
                                          ResumenTransaccionesDiariasRepository resumenRepository) {
        return new StepBuilder("resumenTransaccionesStep", jobRepository)
                .tasklet(new ResumenTransaccionesTasklet(transaccionRepository, anomaliaRepository, resumenRepository),
                        transactionManager)
                .build();
    }
}
