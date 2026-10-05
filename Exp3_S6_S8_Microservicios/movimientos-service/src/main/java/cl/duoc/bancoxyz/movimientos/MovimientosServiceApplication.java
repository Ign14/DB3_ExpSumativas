package cl.duoc.bancoxyz.movimientos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;

/**
 * Microservicio dueno del historial de movimientos.
 *
 * Es el lado interesante de la arquitectura: consume los eventos de retiro que
 * publica cuentas-service y, cuando necesita los datos de la cuenta, los pide
 * por HTTP con tolerancia a fallos.
 */
@SpringBootApplication
@EnableDiscoveryClient
@ComponentScan(basePackages = {"cl.duoc.bancoxyz.movimientos", "cl.duoc.bancoxyz.common.error"})
public class MovimientosServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovimientosServiceApplication.class, args);
    }
}
