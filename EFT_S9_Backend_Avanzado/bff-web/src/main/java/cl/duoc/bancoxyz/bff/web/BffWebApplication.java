package cl.duoc.bancoxyz.bff.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Se escanea también el paquete común para tomar el manejador de errores compartido. */
@SpringBootApplication(scanBasePackages = {"cl.duoc.bancoxyz.bff.web", "cl.duoc.bancoxyz.bff.common"})
public class BffWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffWebApplication.class, args);
    }
}
