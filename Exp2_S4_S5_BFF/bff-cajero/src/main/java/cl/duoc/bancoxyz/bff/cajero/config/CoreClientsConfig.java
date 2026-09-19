package cl.duoc.bancoxyz.bff.cajero.config;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * El cajero solo necesita datos de cuenta/saldo: a diferencia de web y
 * movil, ni siquiera declara un cliente hacia core-movimientos-service,
 * porque el historial de movimientos no es parte de ninguna operacion de
 * cajero.
 */
@Configuration
public class CoreClientsConfig {

    @Bean
    public CuentasApiClient cuentasApiClient(@Value("${bff.core.cuentas-url}") String cuentasUrl) {
        return new CuentasApiClient(RestClient.builder().baseUrl(cuentasUrl).build());
    }
}
