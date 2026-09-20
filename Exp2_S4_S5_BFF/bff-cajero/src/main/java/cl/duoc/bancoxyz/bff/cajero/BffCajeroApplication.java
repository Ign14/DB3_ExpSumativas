package cl.duoc.bancoxyz.bff.cajero;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Se escanea también el paquete común para tomar el manejador de errores compartido. */
@SpringBootApplication(scanBasePackages = {"cl.duoc.bancoxyz.bff.cajero", "cl.duoc.bancoxyz.bff.common"})
public class BffCajeroApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffCajeroApplication.class, args);
    }
}
