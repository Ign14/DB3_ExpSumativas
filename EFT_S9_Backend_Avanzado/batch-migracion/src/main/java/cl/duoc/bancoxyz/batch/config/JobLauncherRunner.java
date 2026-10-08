package cl.duoc.bancoxyz.batch.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Punto de arranque manual de los Jobs, con la politica de finalizacion y de
 * reejecucion automatica del proceso batch.
 *
 * Se ejecuta con el argumento {@code --job=}, lo que permite lanzar cada
 * proceso de forma independiente y repetible (cada ejecucion recibe un
 * JobParameters con timestamp unico):
 * <pre>
 *   --job=transacciones     -> reporteTransaccionesDiariasJob
 *   --job=intereses         -> calculoInteresesMensualesJob
 *   --job=cuentasAnuales    -> estadoCuentaAnualJob
 *   --job=all (por defecto) -> ejecuta los tres jobs en secuencia
 * </pre>
 * El tamano de particion y del pool de hilos se puede variar en cada ejecucion
 * con {@code --batch.partition.grid-size=N} y
 * {@code --batch.partition.thread-pool-size=N}.
 *
 * <h2>Las dos politicas</h2>
 *
 * <b>Reejecucion automatica.</b> Un Job que termina FAILED se relanza hasta
 * {@code batch.job-retry.max-intentos} veces, esperando entre intentos. Esto
 * cubre el fallo critico transitorio, que es distinto del registro invalido: una
 * fila mal formada la resuelve el skip dentro del Step y no detiene nada, pero
 * una base de datos que se cayo a mitad del Job hace fallar el Job entero, y ese
 * es el caso que un operador resolveria volviendo a lanzarlo. Automatizarlo es
 * lo que el caso pide, y tiene un limite a proposito: si el fallo es permanente,
 * insistir para siempre solo retrasa el momento en que alguien se entera.
 *
 * <b>Finalizacion.</b> El proceso termina con codigo de salida 0 solo si todos
 * los Jobs lanzados completaron. Si alguno quedo fallido tras agotar los
 * intentos, sale con 1. Sin esto, un batch que fallo entero terminaria
 * "correctamente" para quien lo invoca —cron, el scheduler del sistema, un paso
 * de CI— y nadie se enteraria hasta que faltaran los datos. El codigo de salida
 * es la unica senal que un orquestador externo mira.
 */
@Slf4j
@Component
public class JobLauncherRunner implements ApplicationRunner {

    private final JobLauncher jobLauncher;
    private final Job reporteTransaccionesDiariasJob;
    private final Job calculoInteresesMensualesJob;
    private final Job estadoCuentaAnualJob;
    private final ConfigurableApplicationContext applicationContext;
    private final int maxIntentos;
    private final long esperaEntreIntentosMs;

    public JobLauncherRunner(JobLauncher jobLauncher,
                             Job reporteTransaccionesDiariasJob,
                             Job calculoInteresesMensualesJob,
                             Job estadoCuentaAnualJob,
                             ConfigurableApplicationContext applicationContext,
                             @Value("${batch.job-retry.max-intentos:2}") int maxIntentos,
                             @Value("${batch.job-retry.espera-ms:3000}") long esperaEntreIntentosMs) {
        this.jobLauncher = jobLauncher;
        this.reporteTransaccionesDiariasJob = reporteTransaccionesDiariasJob;
        this.calculoInteresesMensualesJob = calculoInteresesMensualesJob;
        this.estadoCuentaAnualJob = estadoCuentaAnualJob;
        this.applicationContext = applicationContext;
        this.maxIntentos = maxIntentos;
        this.esperaEntreIntentosMs = esperaEntreIntentosMs;
    }

    @Override
    public void run(ApplicationArguments args) {
        String job = args.containsOption("job") ? args.getOptionValues("job").get(0) : "all";

        List<String> fallidos = new ArrayList<>();
        boolean argumentoValido = true;

        switch (job) {
            case "transacciones" -> lanzarConReintento(reporteTransaccionesDiariasJob, fallidos);
            case "intereses" -> lanzarConReintento(calculoInteresesMensualesJob, fallidos);
            case "cuentasAnuales" -> lanzarConReintento(estadoCuentaAnualJob, fallidos);
            case "all" -> {
                // Los tres se lanzan aunque uno falle. Son independientes entre
                // si —leen archivos distintos y escriben tablas distintas—, asi
                // que detener la secuencia al primer fallo dejaria sin procesar
                // datos que no tienen nada que ver con lo que fallo.
                lanzarConReintento(reporteTransaccionesDiariasJob, fallidos);
                lanzarConReintento(calculoInteresesMensualesJob, fallidos);
                lanzarConReintento(estadoCuentaAnualJob, fallidos);
            }
            default -> {
                log.error("Valor de --job no reconocido: '{}'. Use transacciones|intereses|cuentasAnuales|all",
                        job);
                argumentoValido = false;
            }
        }

        if (!fallidos.isEmpty()) {
            log.error(">> batch: {} job(s) terminaron fallidos tras agotar los reintentos: {}",
                    fallidos.size(), String.join(", ", fallidos));
        } else if (argumentoValido) {
            log.info(">> batch: todos los jobs solicitados completaron correctamente");
        }

        // El pool de hilos usado para el particionado crea hilos "no-daemon",
        // por lo que se cierra explicitamente el contexto al terminar los Jobs
        // para que la JVM finalice (equivalente a un batch por lotes clasico: se
        // ejecuta y termina, no queda un proceso residente).
        int codigoDeSpring = SpringApplication.exit(applicationContext);
        int codigoSalida = (!argumentoValido || !fallidos.isEmpty()) ? 1 : codigoDeSpring;
        System.exit(codigoSalida);
    }

    /**
     * Lanza un Job y lo vuelve a lanzar si termina fallido.
     *
     * Cada intento usa un timestamp nuevo, de modo que Spring Batch lo trate
     * como una ejecucion distinta y no como la continuacion de la anterior. Esa
     * eleccion importa: reanudar la ejecucion fallida reaprovecharia el avance
     * de los Steps que si completaron, pero con particionamiento y escritura por
     * chunks la reanudacion exige que los writers sean idempotentes, y estos
     * escriben con JPA sobre claves generadas. Partir de cero cada vez es mas
     * lento y es correcto; reanudar seria mas rapido y podria duplicar filas.
     */
    private void lanzarConReintento(Job job, List<String> fallidos) {
        for (int intento = 1; intento <= maxIntentos; intento++) {
            BatchStatus estado = lanzar(job, intento);
            if (estado == BatchStatus.COMPLETED) {
                if (intento > 1) {
                    log.warn(">> batch: el job [{}] completo en el intento {} de {}",
                            job.getName(), intento, maxIntentos);
                }
                return;
            }
            if (intento < maxIntentos) {
                log.warn(">> batch: el job [{}] termino en {} - reintentando en {} ms (intento {} de {})",
                        job.getName(), estado, esperaEntreIntentosMs, intento + 1, maxIntentos);
                esperar();
            }
        }
        fallidos.add(job.getName());
    }

    private BatchStatus lanzar(Job job, int intento) {
        try {
            JobParameters parametros = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .addLong("intento", (long) intento)
                    .toJobParameters();
            JobExecution ejecucion = jobLauncher.run(job, parametros);
            return ejecucion.getStatus();
        } catch (Exception e) {
            // Una excepcion aqui no es que el Job haya fallado procesando datos,
            // sino que no se pudo ni lanzar: parametros repetidos, el repositorio
            // de Batch inaccesible. Se trata igual que un fallo para que la
            // politica de reintento y el codigo de salida lo cubran.
            log.error(">> batch: no se pudo ejecutar el job [{}] en el intento {}: {}",
                    job.getName(), intento, e.getMessage(), e);
            return BatchStatus.FAILED;
        }
    }

    private void esperar() {
        try {
            Thread.sleep(esperaEntreIntentosMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
