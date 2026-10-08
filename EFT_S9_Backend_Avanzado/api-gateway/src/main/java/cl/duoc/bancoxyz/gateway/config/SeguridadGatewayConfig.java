package cl.duoc.bancoxyz.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * El gateway tambien valida el JWT, antes de enrutar.
 *
 * Es la primera de dos barreras, no la unica: rechazar aqui un token invalido
 * evita que la peticion consuma recursos del microservicio, pero cada
 * microservicio vuelve a validarlo y ademas comprueba los scopes. El gateway
 * esta construido sobre WebFlux, de ahi que la configuracion sea reactiva y no
 * la misma clase que en los otros servicios.
 *
 * No se abre OPTIONS sin token. Seria lo que hace falta para un preflight CORS,
 * pero no hay ningun frontend servido desde otro origen en esta entrega, y
 * dejarlo abierto solo da alcance anonimo al backend por una funcionalidad que
 * nadie usa. Si se agregara un frontend, el preflight se habilitaria con la
 * configuracion de CORS del gateway y no abriendo un metodo entero.
 */
@Configuration
@EnableWebFluxSecurity
public class SeguridadGatewayConfig {

    @Bean
    SecurityWebFilterChain filtros(ServerHttpSecurity http) {
        return http
                .authorizeExchange(intercambio -> intercambio
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }))
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
