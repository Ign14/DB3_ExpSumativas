package cl.duoc.bancoxyz.bff.common.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de filtros de los tres BFF.
 *
 * Hace falta declararla explicitamente y la razon merece quedar escrita, porque
 * el comportamiento por defecto aqui es una trampa: los BFF dependen de
 * spring-boot-starter-oauth2-client para poder pedir su token, y el solo hecho
 * de tener esa dependencia en el classpath hace que Spring Security proteja
 * todas las rutas con un formulario de login. Sin esta clase, pedir
 * /actuator/health a un BFF devuelve un 302 hacia una pagina de login, y el
 * healthcheck de Docker lo da por caido para siempre. El error no aparece en
 * ningun log: el servicio esta sano y respondiendo, solo que respondiendo otra
 * cosa.
 *
 * <h2>Por que los BFF quedan abiertos</h2>
 *
 * Un BFF no autentica al usuario final en esta entrega, y no es un descuido
 * disimulado. El flujo del sistema es {@code client_credentials}: lo que se
 * autentica son aplicaciones, no personas, y no hay login de usuario en ninguna
 * parte del diseno. Lo que cada BFF protege es el backend, no a si mismo: tiene
 * sus propias credenciales, pide su propio token y solo puede hacer lo que sus
 * scopes permiten, de modo que un canal comprometido no sirve para lo que ese
 * canal no hace.
 *
 * En un despliegue real, el BFF quedaria detras de la autenticacion del canal
 * —la sesion del navegador, el token de la app movil, el certificado del
 * cajero— y esa capa es la que verificaria a la persona. Agregar aqui un login
 * de prueba no acercaria el proyecto a eso: solo pondria una puerta que no
 * corresponde a este nivel y que habria que sacar.
 *
 * Lo que si se hace es no inventar seguridad donde no la hay. CSRF se desactiva
 * porque no hay sesion ni cookie que proteger, y queda dicho en el informe
 * tecnico que la autenticacion del usuario final es el primer agregado que este
 * sistema necesita antes de ver trafico real.
 */
@Configuration
@EnableWebSecurity
public class SeguridadCanalConfig {

    @Bean
    SecurityFilterChain filtrosCanal(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(req -> req.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                // Sin esto, el starter de cliente OAuth2 agrega su propia pagina
                // de login y el formulario por defecto de Spring Security.
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .build();
    }
}
