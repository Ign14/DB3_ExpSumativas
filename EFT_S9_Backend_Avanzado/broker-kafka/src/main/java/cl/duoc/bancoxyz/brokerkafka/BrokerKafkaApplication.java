package cl.duoc.bancoxyz.brokerkafka;

import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;

/**
 * Broker Kafka hospedado en su propio proceso, para la ejecucion local.
 *
 * Cumple el mismo papel que broker-artemis con JMS: ser un componente de red al
 * que los microservicios se conectan por TCP, con la misma configuracion que
 * usarian contra un broker real. En docker-compose este modulo no participa; el
 * broker ahi es la imagen oficial de Apache Kafka en modo KRaft, y los
 * microservicios no cambian una linea porque la direccion del broker viene del
 * Config Server.
 *
 * Los dos topicos se crean al arrancar y no se dejan a la creacion automatica.
 * Un topico creado al vuelo por el primer productor toma las particiones y el
 * factor de replicacion por defecto del broker, que no son necesariamente los
 * que el sistema quiere, y ademas hace que el primer envio falle o se demore
 * mientras el topico aparece. Declararlos aqui los deja listos antes de que
 * ningun productor exista.
 */
@SpringBootApplication
public class BrokerKafkaApplication {

    private static final Logger log = LoggerFactory.getLogger(BrokerKafkaApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(BrokerKafkaApplication.class, args);
    }

    @Bean(destroyMethod = "destroy")
    EmbeddedKafkaZKBroker brokerKafka(@Value("${banco.kafka.puerto:9092}") int puerto,
                                      @Value("${banco.kafka.particiones:3}") int particiones,
                                      @Value("${banco.kafka.host-anunciado:localhost}") String hostAnunciado)
            throws Exception {
        EmbeddedKafkaZKBroker broker = new EmbeddedKafkaZKBroker(
                1, true, particiones,
                TransaccionCompletadaEvento.TOPICO,
                AlertaSeguridadEvento.TOPICO);
        broker.kafkaPorts(puerto);
        // Escucha en todas las interfaces, pero anuncia el nombre por el que los
        // clientes deben volver a buscarlo. Son dos cosas distintas y Kafka las
        // trata por separado: el cliente pide los metadatos del cluster y luego
        // se conecta a la direccion que el broker le devuelve, no a la que uso
        // para preguntar. Si se anunciara 0.0.0.0, el cliente intentaria
        // conectarse a 0.0.0.0 y fallaria.
        broker.brokerProperty("listeners", "PLAINTEXT://0.0.0.0:" + puerto);
        broker.brokerProperty("advertised.listeners", "PLAINTEXT://" + hostAnunciado + ":" + puerto);
        broker.afterPropertiesSet();

        log.info(">> broker-kafka: escuchando en 0.0.0.0:{} y anunciandose como {}:{}",
                puerto, hostAnunciado, puerto);
        log.info(">> broker-kafka: topicos creados = {} y {} ({} particiones cada uno)",
                TransaccionCompletadaEvento.TOPICO, AlertaSeguridadEvento.TOPICO, particiones);
        return broker;
    }
}
