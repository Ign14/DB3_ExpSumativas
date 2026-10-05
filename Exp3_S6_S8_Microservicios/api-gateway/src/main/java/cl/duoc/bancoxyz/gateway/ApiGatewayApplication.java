package cl.duoc.bancoxyz.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Puerta de entrada unica al ecosistema. Los clientes solo conocen este puerto;
 * que haya dos microservicios detras, en que host y en que puerto, es un detalle
 * que resuelve Eureka.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
