package cl.duoc.bancoxyz.movimientos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server del dominio de movimientos. Mismo criterio que en
 * cuentas-service: la autorizacion por scope se aplica en el propio servicio y
 * no solo en el gateway.
 */
@Configuration
@EnableWebSecurity
public class SeguridadConfig {

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(req -> req
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll()
                        // Los endpoints de Resilience4j quedan detras del token:
                        // /actuator/circuitbreakerevents publica los mensajes de
                        // las excepciones internas del servicio.
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_movimientos.read")
                        .requestMatchers(HttpMethod.GET, "/movimientos/**")
                            .hasAuthority("SCOPE_movimientos.read")
                        .requestMatchers(HttpMethod.HEAD, "/movimientos/**")
                            .hasAuthority("SCOPE_movimientos.read")
                        .requestMatchers(HttpMethod.POST, "/movimientos")
                            .hasAuthority("SCOPE_movimientos.write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
                .build();
    }
}
