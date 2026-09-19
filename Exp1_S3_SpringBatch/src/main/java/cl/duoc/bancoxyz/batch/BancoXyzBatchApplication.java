package cl.duoc.bancoxyz.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la aplicación batch del Banco XYZ.
 * <p>
 * El arranque automático de Jobs de Spring Boot está deshabilitado
 * (spring.batch.job.enabled=false); el lanzamiento real de cada proceso
 * lo realiza {@link cl.duoc.bancoxyz.batch.config.JobLauncherRunner} en
 * función del argumento de línea de comandos {@code --job=}.
 */
@SpringBootApplication
public class BancoXyzBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BancoXyzBatchApplication.class, args);
    }
}
