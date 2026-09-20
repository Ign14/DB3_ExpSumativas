package cl.duoc.bancoxyz.bff.web.config;

import cl.duoc.bancoxyz.bff.common.client.CoreRestClientFactory;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Arma los clientes de common-model apuntando a las URLs de los dos
 * servicios core. Las URLs son configurables (application.yml /
 * variables de entorno) para poder mover cada servicio de host sin tocar
 * codigo, y los timeouts los fija {@link CoreRestClientFactory}.
 */
@Configuration
public class CoreClientsConfig {

    @Bean
    public CuentasApiClient cuentasApiClient(@Value("${bff.core.cuentas-url}") String cuentasUrl) {
        return new CuentasApiClient(CoreRestClientFactory.crear(cuentasUrl));
    }

    @Bean
    public MovimientosApiClient movimientosApiClient(@Value("${bff.core.movimientos-url}") String movimientosUrl) {
        return new MovimientosApiClient(CoreRestClientFactory.crear(movimientosUrl));
    }
}
