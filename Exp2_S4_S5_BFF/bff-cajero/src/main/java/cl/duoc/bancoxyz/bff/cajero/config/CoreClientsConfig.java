package cl.duoc.bancoxyz.bff.cajero.config;

import cl.duoc.bancoxyz.bff.common.client.CoreRestClientFactory;
import cl.duoc.bancoxyz.bff.common.client.CuentasApiClient;
import cl.duoc.bancoxyz.bff.common.client.MovimientosApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El cajero integra los dos servicios core, pero por motivos distintos que
 * los otros canales: a core-cuentas-service le pide el saldo y le aplica el
 * debito, y a core-movimientos-service le registra el retiro y le pide solo
 * el ultimo movimiento para el comprobante. En ningun caso expone el
 * historial completo ni los datos del titular.
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
