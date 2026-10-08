package cl.duoc.bancoxyz.broker;

import org.apache.activemq.artemis.core.config.impl.SecurityConfiguration;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.spi.core.security.ActiveMQJAASSecurityManager;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager;
import org.apache.activemq.artemis.spi.core.security.jaas.InVMLoginModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.context.annotation.Bean;

import java.util.Set;

/**
 * Broker Apache ActiveMQ Artemis hospedado en su propio proceso.
 *
 * Artemis embebido, tal como lo configura Spring Boot por defecto, solo acepta
 * conexiones dentro de la misma JVM, asi que no serviria para que dos
 * microservicios distintos se comuniquen. Aqui se le agrega un acceptor TCP
 * para que el broker sea un componente de red como cualquier otro: los
 * microservicios lo consumen en modo "native" y su configuracion es identica
 * corriendo en local o dentro de Docker.
 */
@SpringBootApplication
public class BrokerArtemisApplication {

    private static final Logger log = LoggerFactory.getLogger(BrokerArtemisApplication.class);

    /** Rol con permiso de enviar y consumir en las colas del banco. */
    private static final String ROL = "banco";

    public static void main(String[] args) {
        SpringApplication.run(BrokerArtemisApplication.class, args);
    }

    /**
     * Abre el acceptor TCP y, sobre todo, enciende la seguridad del broker.
     *
     * Spring Boot, al configurar Artemis en modo embebido, llama a
     * {@code setSecurityEnabled(false)}: el broker acepta a cualquiera. Con un
     * acceptor TCP abierto eso deja un camino de escritura al dominio que no
     * pasa por ningun token, porque publicar un evento en la cola de retiros
     * modifica el historial de una cuenta igual que una peticion autenticada.
     * De ahi que la seguridad se vuelva a encender aqui.
     */
    @Bean
    ArtemisConfigurationCustomizer configuracionBroker(@Value("${banco.broker.puerto:61616}") int puerto) {
        return configuracion -> {
            configuracion.setSecurityEnabled(true);
            // "#" es el comodin de Artemis para cualquier direccion. El rol
            // puede enviar y consumir, y crear colas no durables (las que usa
            // un cliente JMS para respuestas temporales), pero no administrar
            // el broker ni borrar colas durables.
            configuracion.putSecurityRoles("#", Set.of(
                    new Role(ROL, true, true, false, false, true, true, false)));
            try {
                configuracion.addAcceptorConfiguration("netty", "tcp://0.0.0.0:" + puerto);
            } catch (Exception ex) {
                // Sin acceptor el broker arranca pero solo acepta conexiones
                // dentro de esta JVM, y los microservicios no podrian hablarse:
                // es preferible no arrancar.
                throw new IllegalStateException(
                        "No se pudo abrir el acceptor TCP del broker en el puerto " + puerto, ex);
            }
            log.info(">> broker-artemis: acceptor TCP en 0.0.0.0:{} con autenticacion activada", puerto);
        };
    }

    @Bean
    ActiveMQSecurityManager seguridadBroker(@Value("${banco.broker.usuario}") String usuario,
                                            @Value("${banco.broker.clave}") String clave) {
        SecurityConfiguration credenciales = new SecurityConfiguration();
        credenciales.addUser(usuario, clave);
        credenciales.addRole(usuario, ROL);
        // Sin usuario por defecto no hay conexiones anonimas.
        credenciales.setDefaultUser(null);
        log.info(">> broker-artemis: un unico usuario '{}' con rol '{}'", usuario, ROL);
        return new ActiveMQJAASSecurityManager(InVMLoginModule.class.getName(), credenciales);
    }

    /**
     * Spring Boot crea el {@code EmbeddedActiveMQ} sin gestor de seguridad y no
     * ofrece un punto de extension para ponerlo. Se inyecta aqui, antes de que
     * el bean ejecute su metodo de arranque, en vez de reemplazar el bean de
     * Boot: asi se conserva todo lo que Boot hace por nosotros (declarar las
     * colas de {@code spring.artemis.embedded.queues}, aplicar los
     * personalizadores) y solo se agrega lo que falta.
     */
    @Bean
    static BeanPostProcessor gestorDeSeguridadEnElBroker(ObjectProvider<ActiveMQSecurityManager> gestor) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String nombre) {
                if (bean instanceof EmbeddedActiveMQ servidor) {
                    servidor.setSecurityManager(gestor.getObject());
                }
                return bean;
            }
        };
    }
}
