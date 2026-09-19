package cl.duoc.bancoxyz.batch.intereses;

import cl.duoc.bancoxyz.batch.common.listener.AnomaliaSkipListener;
import cl.duoc.bancoxyz.batch.common.listener.JobMetricsListener;
import cl.duoc.bancoxyz.batch.common.partition.RangoLineasFlatFileItemReader;
import cl.duoc.bancoxyz.batch.common.partition.RangoLineasPartitioner;
import cl.duoc.bancoxyz.batch.common.repository.RegistroAnomaliaRepository;
import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.config.BatchInfraConfig;
import cl.duoc.bancoxyz.batch.intereses.model.InteresCsv;
import cl.duoc.bancoxyz.batch.intereses.model.InteresCuenta;
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

import java.math.BigDecimal;

/**
 * Job 2 — "Cálculo de Intereses Mensuales". Misma arquitectura de
 * escalado por particiones que el Job 1, aplicada sobre
 * {@code intereses.csv}: aplica la tasa según tipo de cuenta y actualiza
 * el saldo final en la base de datos.
 */
@Configuration
public class InteresesJobConfig {

    private static final String PROCESO = "intereses-mensuales";

    @Bean
    public Job calculoInteresesMensualesJob(JobRepository jobRepository, Step interesesPartitionStep) {
        return new JobBuilder("calculoInteresesMensualesJob", jobRepository)
                .listener(new JobMetricsListener())
                .start(interesesPartitionStep)
                .build();
    }

    @Bean
    public Step interesesPartitionStep(JobRepository jobRepository,
                                        Step interesWorkerStep,
                                        Partitioner interesesPartitioner,
                                        TaskExecutor partitionTaskExecutor,
                                        BatchInfraConfig.PartitionProperties partitionProperties) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(partitionTaskExecutor);
        handler.setStep(interesWorkerStep);
        handler.setGridSize(partitionProperties.getGridSize());

        return new StepBuilder("interesesPartitionStep", jobRepository)
                .partitioner("interesWorkerStep", interesesPartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    public Partitioner interesesPartitioner(@Value("${batch.input.intereses-path}") Resource resource) {
        return new RangoLineasPartitioner(resource);
    }

    @Bean
    public Step interesWorkerStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   ItemReader<InteresCsv> interesItemReader,
                                   InteresItemProcessor interesItemProcessor,
                                   JpaItemWriter<InteresCuenta> interesItemWriter,
                                   AnomaliaSkipListener interesSkipListener,
                                   SimpleRetryPolicy retryPolicy,
                                   BackOffPolicy backOffPolicy,
                                   @Value("${batch.chunk-size:50}") int chunkSize,
                                   BatchInfraConfig.FaultToleranceProperties faultToleranceProperties) {
        return new StepBuilder("interesWorkerStep", jobRepository)
                .<InteresCsv, InteresCuenta>chunk(chunkSize, transactionManager)
                .reader(interesItemReader)
                .processor(interesItemProcessor)
                .writer(interesItemWriter)
                .faultTolerant()
                .skip(RegistroInvalidoException.class)
                .skipLimit(faultToleranceProperties.getSkipLimit())
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .listener(interesSkipListener)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<InteresCsv> interesItemReader(
            @Value("#{stepExecutionContext['startLine']}") Integer startLine,
            @Value("#{stepExecutionContext['endLine']}") Integer endLine,
            @Value("${batch.input.intereses-path}") Resource resource) {

        RangoLineasFlatFileItemReader<InteresCsv> reader = new RangoLineasFlatFileItemReader<>(startLine, endLine, 1);
        reader.setResource(resource);
        reader.setName("interesItemReader");

        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("cuentaId", "nombre", "saldo", "edad", "tipo");

        BeanWrapperFieldSetMapper<InteresCsv> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(InteresCsv.class);

        DefaultLineMapper<InteresCsv> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);
        reader.setLineMapper(lineMapper);

        return reader;
    }

    @Bean
    public InteresItemProcessor interesItemProcessor(
            @Value("${batch.intereses.tasa-ahorro}") BigDecimal tasaAhorro,
            @Value("${batch.intereses.tasa-prestamo}") BigDecimal tasaPrestamo,
            @Value("${batch.intereses.tasa-hipoteca}") BigDecimal tasaHipoteca) {
        return new InteresItemProcessor(tasaAhorro, tasaPrestamo, tasaHipoteca);
    }

    @Bean
    public JpaItemWriter<InteresCuenta> interesItemWriter(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<InteresCuenta>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(true)
                .build();
    }

    @Bean
    public AnomaliaSkipListener interesSkipListener(RegistroAnomaliaRepository repository) {
        return new AnomaliaSkipListener(PROCESO, repository);
    }
}
