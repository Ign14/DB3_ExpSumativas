package cl.duoc.bancoxyz.bff.movil.config;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.GatewayClientFactory;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

/**
 * El canal movil consume cuentas y movimientos, y deliberadamente no consume
 * clientes-service.
 *
 * Su pantalla de resumen muestra saldo y ultimos movimientos. El perfil
 * comercial del titular no aparece ahi, asi que pedirlo solo sumaria una
 * llamada remota y datos personales a un payload que este canal mantiene
 * pequeno a proposito. Si manana la app agrega una pantalla de perfil, el
 * cliente se agrega aqui y el token del canal ya tiene el scope de lectura.
 */
@Configuration
public class CoreClientsConfig {

    private static final String REGISTRO = "movil";

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
}
