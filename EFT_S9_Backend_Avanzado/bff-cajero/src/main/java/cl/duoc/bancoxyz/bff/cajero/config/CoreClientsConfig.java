package cl.duoc.bancoxyz.bff.cajero.config;

import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.GatewayClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

/**
 * El cajero solo necesita un cliente: el del dominio de cuentas.
 *
 * Ya no construye el cliente de movimientos, y eso es consecuencia de la
 * arquitectura de eventos. Antes el canal tenia que anotar el movimiento
 * despues de debitar, porque nadie mas lo iba a hacer; ahora cuentas-service
 * publica el evento del retiro y pagos-service lo consume de la cola. El canal
 * hace una sola llamada y el historial se actualiza igual.
 */
@Configuration
public class CoreClientsConfig {

    /** Registro OAuth2 del canal, declarado en application.yml. */
    private static final String REGISTRO = "cajero";

    @Bean
    CuentasApiClient cuentasApiClient(@Value("${bff.gateway.url}") String urlGateway,
                                      OAuth2AuthorizedClientManager gestor) {
        return new CuentasApiClient(GatewayClientFactory.crear(urlGateway, gestor, REGISTRO));
    }
}
