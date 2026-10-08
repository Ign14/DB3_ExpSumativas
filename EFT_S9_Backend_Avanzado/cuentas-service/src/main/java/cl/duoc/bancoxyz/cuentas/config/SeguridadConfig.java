package cl.duoc.bancoxyz.cuentas.config;

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
 * Este microservicio es un Resource Server: no autentica a nadie, solo valida
 * la firma y el issuer del JWT que trae la peticion y autoriza segun los scopes
 * que el token declara.
 *
 * La autorizacion se aplica aqui y no solo en el gateway. Si estuviera solo en
 * el gateway, cualquier proceso dentro de la red interna podria llamar a este
 * servicio sin token: el gateway seria una puerta con un muro de un metro al
 * lado.
 */
@Configuration
@EnableWebSecurity
public class SeguridadConfig {

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(req -> req
                        // Solo la salud queda abierta, que es lo que consulta el
                        // healthcheck de Docker. El resto del actuator no: entre
                        // otras cosas expone el detalle de los componentes y los
                        // eventos del circuit breaker con sus mensajes de error.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_cuentas.read")
                        // HEAD va junto a GET: Spring MVC lo resuelve contra el
                        // mismo @GetMapping, y sin declararlo caeria en denyAll
                        // devolviendo 403 por un endpoint que el README publica.
                        .requestMatchers(HttpMethod.GET, "/cuentas/**")
                            .hasAuthority("SCOPE_cuentas.read")
                        .requestMatchers(HttpMethod.HEAD, "/cuentas/**")
                            .hasAuthority("SCOPE_cuentas.read")
                        .requestMatchers(HttpMethod.POST, "/cuentas/*/retiro")
                            .hasAuthority("SCOPE_cuentas.write")
                        // Las primitivas de liquidacion exigen un scope propio y
                        // no el de escritura del retiro: mover saldo sin pasar
                        // por el limite por operacion ni dejar el hecho en el
                        // topico es algo que solo debe poder hacer el servicio
                        // dueno de la operacion que se esta liquidando.
                        .requestMatchers(HttpMethod.POST, "/cuentas/*/abono", "/cuentas/*/cargo")
                            .hasAuthority("SCOPE_cuentas.liquidar")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }))
                // Sin sesion: cada peticion se autoriza por su propio token.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
                .build();
    }
}
