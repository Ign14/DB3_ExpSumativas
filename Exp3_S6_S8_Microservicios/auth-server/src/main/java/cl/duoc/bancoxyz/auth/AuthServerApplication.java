package cl.duoc.bancoxyz.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Servidor de autorizacion OAuth2. Es el unico componente del ecosistema que
 * conoce credenciales: los demas microservicios solo validan la firma del token
 * que emite este servicio.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class AuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
