package cl.duoc.bancoxyz.broker;

import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Salud del broker, consultada sobre el propio servidor y no abriendo una
 * conexion JMS.
 *
 * El indicador que trae Spring Boot para JMS usa la factoria de conexiones
 * embebida, que no lleva credenciales: con la autenticacion del broker
 * encendida, ese indicador reporta DOWN aunque el broker este perfectamente
 * sano, y el healthcheck de docker-compose nunca pasaria. Preguntarle al
 * servidor si esta activo es ademas una comprobacion mas directa que conectarse
 * como cliente.
 */
@Component("broker")
public class SaludBrokerIndicator implements HealthIndicator {

    private final EmbeddedActiveMQ servidorEmbebido;

    public SaludBrokerIndicator(EmbeddedActiveMQ servidorEmbebido) {
        this.servidorEmbebido = servidorEmbebido;
    }

    @Override
    public Health health() {
        ActiveMQServer servidor = servidorEmbebido.getActiveMQServer();
        if (servidor == null || !servidor.isActive() || !servidor.isStarted()) {
            return Health.down().withDetail("motivo", "el broker no esta activo").build();
        }
        return Health.up()
                .withDetail("version", servidor.getVersion().getFullVersion())
                .withDetail("colas", servidor.getActiveMQServerControl().getQueueNames().length)
                .build();
    }
}
