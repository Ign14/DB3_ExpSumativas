package cl.duoc.bancoxyz.bff.web.config;

import cl.duoc.bancoxyz.bff.common.client.ClientesApiClient;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.GatewayClientFactory;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import cl.duoc.bancoxyz.bff.common.client.PagosApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

/**
 * El canal web es el unico que consume los cuatro dominios: cuentas,
 * movimientos, clientes y pagos. Es la consola administrativa y la unica con
 * scopes de escritura en los tres dominios de negocio.
 */
@Configuration
public class CoreClientsConfig {

    private static final String REGISTRO = "web";

    @Bean
    CuentasApiClient cuentasApiClient(@Value("${bff.gateway.url}") String urlGateway,
                                      OAuth2AuthorizedClientManager gestor) {
        return new CuentasApiClient(GatewayClientFactory.crear(urlGateway, gestor, REGISTRO));
    }

    @Bean
    MovimientosApiClient movimientosApiClient(@Value("${bff.gateway.url}") String urlGateway,
                                              OAuth2AuthorizedClientManager gestor) {
        return new MovimientosApiClient(GatewayClientFactory.crear(urlGateway, gestor, REGISTRO));
    }

    @Bean
    ClientesApiClient clientesApiClient(@Value("${bff.gateway.url}") String urlGateway,
                                        OAuth2AuthorizedClientManager gestor) {
        return new ClientesApiClient(GatewayClientFactory.crear(urlGateway, gestor, REGISTRO));
    }

    @Bean
    PagosApiClient pagosApiClient(@Value("${bff.gateway.url}") String urlGateway,
                                  OAuth2AuthorizedClientManager gestor) {
        return new PagosApiClient(GatewayClientFactory.crear(urlGateway, gestor, REGISTRO));
    }
}
