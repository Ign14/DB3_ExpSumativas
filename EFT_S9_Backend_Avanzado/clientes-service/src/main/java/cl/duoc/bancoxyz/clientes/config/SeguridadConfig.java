package cl.duoc.bancoxyz.clientes.config;

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
 * Resource Server del dominio de clientes, con las mismas reglas que el resto
 * del ecosistema: valida firma e issuer del JWT y autoriza por scope.
 *
 * La separacion entre lectura y escritura importa especialmente aqui, porque
 * este servicio guarda datos personales: el canal movil puede leer el perfil
 * para mostrarlo, pero solo el canal web administrativo puede modificar el
 * nombre del titular.
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
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_clientes.read")
                        .requestMatchers(HttpMethod.GET, "/clientes/**")
                            .hasAuthority("SCOPE_clientes.read")
                        .requestMatchers(HttpMethod.HEAD, "/clientes/**")
                            .hasAuthority("SCOPE_clientes.read")
                        .requestMatchers(HttpMethod.PUT, "/clientes/*/nombre")
                            .hasAuthority("SCOPE_clientes.write")
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
