package cl.duoc.bancoxyz.batch.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Punto de arranque manual de los Jobs. Se ejecuta con el argumento
 * {@code --job=}, lo que permite lanzar cada proceso de forma
 * independiente y repetible (cada ejecución recibe un JobParameters con
 * timestamp único):
 * <pre>
 *   --job=transacciones     -> reporteTransaccionesDiariasJob
 *   --job=intereses         -> calculoInteresesMensualesJob
 *   --job=cuentasAnuales    -> estadoCuentaAnualJob
 *   --job=all (por defecto) -> ejecuta los tres jobs en secuencia
 * </pre>
 * El tamaño de partición y del pool de hilos se puede variar en cada
 * ejecución con {@code --batch.partition.grid-size=N} y
 * {@code --batch.partition.thread-pool-size=N}, lo que se usó para
 * comparar configuraciones y elegir la óptima (ver README).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLauncherRunner implements ApplicationRunner {

    private final JobLauncher jobLauncher;
    private final Job reporteTransaccionesDiariasJob;
    private final Job calculoInteresesMensualesJob;
    private final Job estadoCuentaAnualJob;
    private final ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String job = args.containsOption("job") ? args.getOptionValues("job").get(0) : "all";

        switch (job) {
            case "transacciones" -> lanzar(reporteTransaccionesDiariasJob);
            case "intereses" -> lanzar(calculoInteresesMensualesJob);
            case "cuentasAnuales" -> lanzar(estadoCuentaAnualJob);
            case "all" -> {
                lanzar(reporteTransaccionesDiariasJob);
                lanzar(calculoInteresesMensualesJob);
                lanzar(estadoCuentaAnualJob);
            }
            default -> log.error("Valor de --job no reconocido: '{}'. Use transacciones|intereses|cuentasAnuales|all", job);
        }

        // El pool de hilos usado para el particionado crea hilos "no-daemon",
        // por lo que se cierra explícitamente el contexto al terminar los
        // Jobs para que la JVM finalice (equivalente a un batch por lotes
        // clásico: se ejecuta y termina, no queda un proceso residente).
        int codigoSalida = SpringApplication.exit(applicationContext);
        System.exit(codigoSalida);
    }

    private void lanzar(Job job) {
        try {
            JobParameters parametros = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
            jobLauncher.run(job, parametros);
        } catch (Exception e) {
            log.error("Error ejecutando job [{}]: {}", job.getName(), e.getMessage(), e);
        }
    }
}
