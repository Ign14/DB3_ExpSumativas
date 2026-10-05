package cl.duoc.bancoxyz.cuentas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;

/**
 * Microservicio dueno del dominio de cuentas.
 *
 * Se incluye el paquete comun en el escaneo para heredar el manejador de
 * errores compartido, de modo que los dos microservicios respondan igual ante
 * el mismo tipo de error.
 */
@SpringBootApplication
@EnableDiscoveryClient
@ComponentScan(basePackages = {"cl.duoc.bancoxyz.cuentas", "cl.duoc.bancoxyz.common.error"})
public class CuentasServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CuentasServiceApplication.class, args);
    }
}
