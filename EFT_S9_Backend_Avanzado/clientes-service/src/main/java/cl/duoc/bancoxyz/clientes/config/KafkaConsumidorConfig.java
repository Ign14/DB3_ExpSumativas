package cl.duoc.bancoxyz.clientes.config;

import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumo de los dos topicos del sistema.
 *
 * Hay una factoria por tipo de evento y en cada una el deserializador sabe de
 * antemano a que clase convertir. La alternativa habitual es dejar que el
 * consumidor lea la clase desde el encabezado que escribe el productor, y es
 * justamente lo que se evita aqui: ese encabezado lleva el nombre completo de
 * la clase del productor, asi que un paquete renombrado en el otro servicio
 * rompe este, y un mensaje con un encabezado manipulado le pediria a este
 * proceso instanciar una clase cualquiera del classpath. Fijar el tipo en el
 * consumidor convierte el contrato en algo que este servicio declara, no en
 * algo que acepta de quien le escribe.
 *
 * Cada deserializador va envuelto en {@link ErrorHandlingDeserializer}. Sin eso,
 * un mensaje con JSON invalido hace fallar la deserializacion antes de que el
 * listener exista, el contenedor reintenta el mismo offset para siempre y el
 * consumo del topico se detiene: un solo mensaje mal formado basta para dejar
 * de procesar todo lo que venga detras. Envuelto, el fallo llega al manejador de
 * errores del contenedor, que lo registra y avanza el offset.
 */
@Configuration
public class KafkaConsumidorConfig {

    private final KafkaProperties propiedades;

    public KafkaConsumidorConfig(KafkaProperties propiedades) {
        this.propiedades = propiedades;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TransaccionCompletadaEvento> factoriaTransacciones() {
        return factoriaPara(TransaccionCompletadaEvento.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, AlertaSeguridadEvento> factoriaAlertas() {
        return factoriaPara(AlertaSeguridadEvento.class);
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> factoriaPara(Class<T> tipo) {
        Map<String, Object> config = new HashMap<>(propiedades.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, tipo.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        // Solo los paquetes propios: el valor por defecto de spring-kafka es "*".
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "cl.duoc.bancoxyz.common.evento");

        ConsumerFactory<String, T> factoriaConsumidor = new DefaultKafkaConsumerFactory<>(config);
        ConcurrentKafkaListenerContainerFactory<String, T> factoria =
                new ConcurrentKafkaListenerContainerFactory<>();
        factoria.setConsumerFactory(factoriaConsumidor);
        return factoria;
    }
}
