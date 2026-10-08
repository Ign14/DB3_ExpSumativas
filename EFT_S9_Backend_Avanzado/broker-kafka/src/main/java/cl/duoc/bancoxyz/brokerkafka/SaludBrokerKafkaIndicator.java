package cl.duoc.bancoxyz.brokerkafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Salud del broker: no basta con que el proceso este vivo.
 *
 * Un indicador que devolviera UP por el solo hecho de que el bean existe seria
 * un adorno: el bean existe desde antes de que el puerto acepte conexiones. Aqui
 * se le pregunta al broker por sus topicos con un cliente de administracion
 * real, que es la misma operacion que hara el primer productor. Si eso responde,
 * el broker esta listo de verdad, y docker-compose y los scripts de evidencia
 * pueden esperar este endpoint antes de arrancar a los microservicios.
 */
@Component
public class SaludBrokerKafkaIndicator implements HealthIndicator {

    private final EmbeddedKafkaZKBroker broker;

    public SaludBrokerKafkaIndicator(EmbeddedKafkaZKBroker broker) {
        this.broker = broker;
    }

    @Override
    public Health health() {
        String direccion = broker.getBrokersAsString();
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, direccion,
                        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000,
                        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 4000))) {
            Set<String> topicos = admin.listTopics().names().get(5, TimeUnit.SECONDS);
            return Health.up()
                    .withDetail("direccion", direccion)
                    .withDetail("topicos", topicos)
                    .build();
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            return Health.down()
                    .withDetail("direccion", direccion)
                    .withDetail("motivo", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .build();
        }
    }
}
