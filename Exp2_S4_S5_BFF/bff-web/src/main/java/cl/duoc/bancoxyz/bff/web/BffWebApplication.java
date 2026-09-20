package cl.duoc.bancoxyz.bff.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Se incluye el paquete {@code ...bff.common} en el escaneo para tomar el
 * manejador de errores compartido (BffExceptionHandler) en vez de repetir
 * uno identico en cada canal.
 */
@SpringBootApplication(scanBasePackages = {"cl.duoc.bancoxyz.bff.web", "cl.duoc.bancoxyz.bff.common"})
public class BffWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffWebApplication.class, args);
    }
}
