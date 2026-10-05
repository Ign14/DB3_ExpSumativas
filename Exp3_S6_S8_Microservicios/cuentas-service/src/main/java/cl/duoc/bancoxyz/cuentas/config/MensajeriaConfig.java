package cl.duoc.bancoxyz.cuentas.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

/**
 * Los eventos viajan como JSON en un TextMessage, no como objetos serializados
 * de Java: asi el consumidor puede evolucionar sin quedar atado al
 * serialVersionUID del productor, y el contenido de la cola se puede leer con
 * cualquier herramienta.
 *
 * El nombre de la clase viaja en la propiedad "_tipoEvento" para que el
 * consumidor sepa a que deserializar. Funciona porque ambos microservicios
 * dependen del modulo common-events, que es el unico contrato que comparten.
 */
@Configuration
public class MensajeriaConfig {

    @Bean
    MessageConverter conversorJson() {
        MappingJackson2MessageConverter conversor = new MappingJackson2MessageConverter();
        conversor.setTargetType(MessageType.TEXT);
        conversor.setTypeIdPropertyName("_tipoEvento");
        return conversor;
    }
}
