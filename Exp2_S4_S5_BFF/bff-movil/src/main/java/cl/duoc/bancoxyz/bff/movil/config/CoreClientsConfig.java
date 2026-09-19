package cl.duoc.bancoxyz.bff.movil.config;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CoreClientsConfig {

    @Bean
    public CuentasApiClient cuentasApiClient(@Value("${bff.core.cuentas-url}") String cuentasUrl) {
        return new CuentasApiClient(RestClient.builder().baseUrl(cuentasUrl).build());
    }

    @Bean
    public MovimientosApiClient movimientosApiClient(@Value("${bff.core.movimientos-url}") String movimientosUrl) {
        return new MovimientosApiClient(RestClient.builder().baseUrl(movimientosUrl).build());
    }
}
