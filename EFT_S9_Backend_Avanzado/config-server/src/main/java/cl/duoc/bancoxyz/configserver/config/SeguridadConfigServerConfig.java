package cl.duoc.bancoxyz.configserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * El Config Server pide autenticacion basica.
 *
 * No es un detalle opcional: este servidor devuelve la configuracion completa de
 * todos los microservicios, y ahi dentro viaja el secreto del cliente OAuth2 que
 * usa pagos-service. Sin autenticacion, un GET anonimo a
 * /pagos-service/default entrega una credencial con la que se puede pedir
 * un JWT valido al auth-server, y todo el esquema de scopes queda neutralizado
 * por la puerta de al lado.
 *
 * Solo el endpoint de salud queda abierto, porque es lo que consulta el
 * healthcheck de docker-compose para encadenar el orden de arranque.
 */
@Configuration
@EnableWebSecurity
public class SeguridadConfigServerConfig {

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(req -> req
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                // Los clientes de configuracion son procesos, no navegadores: no
                // hay formulario ni sesion de la que proteger, y exigir el token
                // CSRF solo rompe las peticiones de arranque.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
