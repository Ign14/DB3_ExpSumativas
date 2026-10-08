package cl.duoc.bancoxyz.discovery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Eureka pide autenticacion basica.
 *
 * El registro de servicios no es solo informativo: es quien decide a donde va el
 * trafico. Con la API REST abierta, cualquiera que alcance el puerto puede
 * desregistrar una instancia —y dejar al gateway respondiendo 503 con tokens
 * perfectamente validos— o registrar un host propio bajo el nombre de un
 * microservicio y recibir las peticiones con el Bearer token real en la
 * cabecera. Ninguna de las dos cosas la detiene el JWT, porque ocurren antes de
 * que el JWT entre en juego.
 *
 * Los microservicios se autentican con las credenciales embebidas en la URL de
 * Eureka (http://usuario:clave@host:8761/eureka/), que define la configuracion
 * central.
 */
@Configuration
@EnableWebSecurity
public class SeguridadEurekaConfig {

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(req -> req
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                // Los clientes de Eureka registran y renuevan su lease con POST y
                // PUT sin token CSRF: dejarlo activo rompe el registro completo.
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
