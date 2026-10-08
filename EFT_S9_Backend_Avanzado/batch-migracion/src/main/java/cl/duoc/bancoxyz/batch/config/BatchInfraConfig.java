package cl.duoc.bancoxyz.batch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;

/**
 * Beans de infraestructura compartidos por los tres Jobs: parámetros de
 * escalado (grid size / tamaño del pool de hilos), política de reintento
 * con backoff exponencial para errores transitorios de base de datos, y
 * el {@link StepBuilder} base usado por cada configuración de proceso.
 */
@Configuration
public class BatchInfraConfig {

    @Bean
    @ConfigurationProperties(prefix = "batch.partition")
    public PartitionProperties partitionProperties() {
        return new PartitionProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "batch.fault-tolerance")
    public FaultToleranceProperties faultToleranceProperties() {
        return new FaultToleranceProperties();
    }

    /**
     * Pool de hilos que ejecuta en paralelo las particiones de cada Step
     * particionado (TaskExecutorPartitionHandler). El tamaño es configurable
     * vía {@code batch.partition.thread-pool-size} para poder comparar
     * distintas configuraciones (ver README, sección de benchmarking).
     */
    @Bean
    public ThreadPoolTaskExecutor partitionTaskExecutor(@Value("${batch.partition.thread-pool-size:4}") int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setThreadNamePrefix("batch-partition-");
        executor.initialize();
        return executor;
    }

    /**
     * Política de reintento: ante errores transitorios de acceso a datos
     * (por ejemplo, una desconexión momentánea del motor de base de datos),
     * se reintenta hasta {@code batch.fault-tolerance.retry-limit} veces.
     */
    @Bean
    public SimpleRetryPolicy retryPolicy(@Value("${batch.fault-tolerance.retry-limit:3}") int retryLimit) {
        return new SimpleRetryPolicy(retryLimit, Collections.singletonMap(TransientDataAccessException.class, true));
    }

    /**
     * Backoff exponencial entre reintentos (100ms, 200ms, 400ms, ...),
     * para no insistir de inmediato ni sobrecargar el sistema si el
     * problema persiste.
     */
    @Bean
    public ExponentialBackOffPolicy backOffPolicy() {
        ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
        policy.setInitialInterval(100);
        policy.setMultiplier(2.0);
        policy.setMaxInterval(2000);
        return policy;
    }

    /** Propiedades de escalado/particionado, mapeadas desde application.yml. */
    public static class PartitionProperties {
        private int gridSize = 4;
        private int threadPoolSize = 4;

        public int getGridSize() {
            return gridSize;
        }

        public void setGridSize(int gridSize) {
            this.gridSize = gridSize;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }
    }

    /** Propiedades de tolerancia a fallos, mapeadas desde application.yml. */
    public static class FaultToleranceProperties {
        private int skipLimit = 200;
        private int retryLimit = 3;

        public int getSkipLimit() {
            return skipLimit;
        }

        public void setSkipLimit(int skipLimit) {
            this.skipLimit = skipLimit;
        }

        public int getRetryLimit() {
            return retryLimit;
        }

        public void setRetryLimit(int retryLimit) {
            this.retryLimit = retryLimit;
        }
    }
}
