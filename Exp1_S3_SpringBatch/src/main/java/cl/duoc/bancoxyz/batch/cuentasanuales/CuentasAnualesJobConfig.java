package cl.duoc.bancoxyz.batch.cuentasanuales;

import cl.duoc.bancoxyz.batch.common.listener.AnomaliaSkipListener;
import cl.duoc.bancoxyz.batch.common.listener.JobMetricsListener;
import cl.duoc.bancoxyz.batch.common.partition.RangoLineasFlatFileItemReader;
import cl.duoc.bancoxyz.batch.common.partition.RangoLineasPartitioner;
import cl.duoc.bancoxyz.batch.common.repository.RegistroAnomaliaRepository;
import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.config.BatchInfraConfig;
import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCsv;
import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCuentaAnual;
import cl.duoc.bancoxyz.batch.cuentasanuales.repository.EstadoCuentaAnualRepository;
import cl.duoc.bancoxyz.batch.cuentasanuales.repository.MovimientoCuentaAnualRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
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
 * Job 3 — "Generación de Estados de Cuenta Anuales". Misma arquitectura de
 * particionado que los jobs 1 y 2 sobre {@code cuentas_anuales.csv}, más un
 * Step final de agregación que compila el estado de cuenta anual por
 * cuenta/año.
 */
@Configuration
public class CuentasAnualesJobConfig {

    private static final String PROCESO = "estados-cuenta-anuales";

    @Bean
    public Job estadoCuentaAnualJob(JobRepository jobRepository,
                                     Step cuentasAnualesPartitionStep,
                                     Step estadoCuentaAnualStep) {
        return new JobBuilder("estadoCuentaAnualJob", jobRepository)
                .listener(new JobMetricsListener())
                .start(cuentasAnualesPartitionStep)
                .next(estadoCuentaAnualStep)
                .build();
    }

    @Bean
    public Step cuentasAnualesPartitionStep(JobRepository jobRepository,
                                             Step movimientoWorkerStep,
                                             Partitioner cuentasAnualesPartitioner,
                                             TaskExecutor partitionTaskExecutor,
                                             BatchInfraConfig.PartitionProperties partitionProperties) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(partitionTaskExecutor);
        handler.setStep(movimientoWorkerStep);
        handler.setGridSize(partitionProperties.getGridSize());

        return new StepBuilder("cuentasAnualesPartitionStep", jobRepository)
                .partitioner("movimientoWorkerStep", cuentasAnualesPartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    public Partitioner cuentasAnualesPartitioner(@Value("${batch.input.cuentas-anuales-path}") Resource resource) {
        return new RangoLineasPartitioner(resource);
    }

    @Bean
    public Step movimientoWorkerStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      ItemReader<MovimientoCsv> movimientoItemReader,
                                      MovimientoItemProcessor movimientoItemProcessor,
                                      JpaItemWriter<MovimientoCuentaAnual> movimientoItemWriter,
                                      AnomaliaSkipListener cuentasAnualesSkipListener,
                                      SimpleRetryPolicy retryPolicy,
                                      BackOffPolicy backOffPolicy,
                                      @Value("${batch.chunk-size:50}") int chunkSize,
                                      BatchInfraConfig.FaultToleranceProperties faultToleranceProperties) {
        return new StepBuilder("movimientoWorkerStep", jobRepository)
                .<MovimientoCsv, MovimientoCuentaAnual>chunk(chunkSize, transactionManager)
                .reader(movimientoItemReader)
                .processor(movimientoItemProcessor)
                .writer(movimientoItemWriter)
                .faultTolerant()
                .skip(RegistroInvalidoException.class)
                .skipLimit(faultToleranceProperties.getSkipLimit())
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .listener(cuentasAnualesSkipListener)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<MovimientoCsv> movimientoItemReader(
            @Value("#{stepExecutionContext['startLine']}") Integer startLine,
            @Value("#{stepExecutionContext['endLine']}") Integer endLine,
            @Value("${batch.input.cuentas-anuales-path}") Resource resource) {

        RangoLineasFlatFileItemReader<MovimientoCsv> reader = new RangoLineasFlatFileItemReader<>(startLine, endLine, 1);
        reader.setResource(resource);
        reader.setName("movimientoItemReader");

        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("cuentaId", "fecha", "transaccion", "monto", "descripcion");

        BeanWrapperFieldSetMapper<MovimientoCsv> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(MovimientoCsv.class);

        DefaultLineMapper<MovimientoCsv> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);
        reader.setLineMapper(lineMapper);

        return reader;
    }

    @Bean
    public MovimientoItemProcessor movimientoItemProcessor() {
        return new MovimientoItemProcessor();
    }

    @Bean
    public JpaItemWriter<MovimientoCuentaAnual> movimientoItemWriter(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<MovimientoCuentaAnual>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(true)
                .build();
    }

    @Bean
    public AnomaliaSkipListener cuentasAnualesSkipListener(RegistroAnomaliaRepository repository) {
        return new AnomaliaSkipListener(PROCESO, repository);
    }

    @Bean
    public Step estadoCuentaAnualStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       MovimientoCuentaAnualRepository movimientoRepository,
                                       EstadoCuentaAnualRepository estadoRepository) {
        return new StepBuilder("estadoCuentaAnualStep", jobRepository)
                .tasklet(new EstadoCuentaAnualTasklet(movimientoRepository, estadoRepository), transactionManager)
                .build();
    }
}
