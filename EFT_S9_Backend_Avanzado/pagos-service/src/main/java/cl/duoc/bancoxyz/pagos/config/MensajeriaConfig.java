package cl.duoc.bancoxyz.pagos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

/**
 * El conversor debe ser el mismo que usa el productor, con el mismo nombre de
 * propiedad para el tipo: es la otra mitad del contrato de la cola.
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
