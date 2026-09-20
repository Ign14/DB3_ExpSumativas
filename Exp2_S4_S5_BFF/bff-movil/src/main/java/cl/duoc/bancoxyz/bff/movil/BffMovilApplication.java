package cl.duoc.bancoxyz.bff.movil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Se incluye el paquete {@code ...bff.common} en el escaneo para tomar el
 * manejador de errores compartido (BffExceptionHandler) en vez de repetir
 * uno identico en cada canal.
 */
@SpringBootApplication(scanBasePackages = {"cl.duoc.bancoxyz.bff.movil", "cl.duoc.bancoxyz.bff.common"})
public class BffMovilApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffMovilApplication.class, args);
    }
}
