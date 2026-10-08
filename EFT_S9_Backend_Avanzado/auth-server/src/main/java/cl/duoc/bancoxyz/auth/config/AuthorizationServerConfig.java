package cl.duoc.bancoxyz.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

/**
 * Configuracion del servidor de autorizacion.
 *
 * El flujo elegido es {@code client_credentials}: en este sistema quien pide un
 * token no es una persona frente a una pantalla de login, sino una aplicacion
 * (el canal web, el cajero) o otro microservicio. No hay usuario final que
 * autorice nada, asi que ni authorization_code ni PKCE aportarian algo; lo que
 * se autentica es la aplicacion contra el servidor de autorizacion.
 */
@Configuration
public class AuthorizationServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerConfig.class);

    /**
     * Cadena de filtros de los endpoints OAuth2 (/oauth2/token, /oauth2/jwks,
     * /.well-known/...). Va primero porque solo debe atender esas rutas.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        return http.build();
    }

    /**
     * Todo lo que no sean los endpoints OAuth2.
     *
     * Solo la salud queda abierta, y el resto se deniega sin mas. No hay
     * {@code httpBasic} ni formulario de login a proposito: este servidor no tiene
     * usuarios: autentica aplicaciones por {@code client_credentials} en la cadena
     * anterior. Ofrecer una autenticacion basica que ningun usuario puede
     * satisfacer seria sugerir en el codigo una puerta que no existe.
     *
     * El actuator se lista endpoint por endpoint y no como {@code /actuator/**}
     * porque este es el unico componente que guarda los secretos de los clientes:
     * si manana alguien agrega {@code env} o {@code configprops} a los endpoints
     * expuestos, no deberian quedar anonimos por un comodin escrito hoy.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    SecurityFilterChain resto(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/**")
                .authorizeHttpRequests(req -> req
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * Clientes registrados, uno por canal y uno por servicio que llama a otro.
     *
     * Cada uno recibe solo los scopes que su canal necesita, y esa es la forma
     * en que el sistema resuelve el requerimiento de autenticacion y
     * autorizacion especificas por canal. La diferencia no es cosmetica:
     *
     * - El canal web es la consola administrativa: lee y escribe en los tres
     *   dominios, incluidos los datos personales del titular.
     * - El canal movil solo lee. Una aplicacion que vive en un telefono que se
     *   pierde, se presta o se roba no deberia poder mover dinero ni editar el
     *   nombre de nadie con el token que lleva guardado.
     * - El cajero consulta saldo y retira, y nada mas. No puede leer el
     *   historial de movimientos ni los datos del cliente: un cajero
     *   comprometido no debe convertirse en una fuente de informacion personal.
     * - pagos-service es un servicio, no un canal. Pide token para hablar con
     *   cuentas-service y tiene lo justo para las operaciones que orquesta: leer
     *   una cuenta y liquidar cargos y abonos. Es el unico con
     *   {@code cuentas.liquidar}, el scope que permite mover saldo sin pasar por
     *   el limite por operacion del retiro.
     *
     * El resultado concreto se puede comprobar en la evidencia: el token del
     * cajero recibe 403 contra el historial de movimientos y contra el padron de
     * clientes, aunque la firma del token sea perfectamente valida.
     */
    @Bean
    RegisteredClientRepository registeredClientRepository(
            PasswordEncoder encoder,
            @Value("${banco.oauth2.clientes.banco-web}") String secretoWeb,
            @Value("${banco.oauth2.clientes.banco-movil}") String secretoMovil,
            @Value("${banco.oauth2.clientes.cajero}") String secretoCajero,
            @Value("${banco.oauth2.clientes.pagos-service}") String secretoPagos) {
        TokenSettings tokenSettings = TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .accessTokenTimeToLive(Duration.ofMinutes(30))
                .build();

        // Canal web/administrativo: lee y escribe en los tres dominios.
        RegisteredClient web = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("banco-web-client")
                .clientSecret(encoder.encode(secretoWeb))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("cuentas.read")
                .scope("cuentas.write")
                .scope("movimientos.read")
                .scope("movimientos.write")
                .scope("clientes.read")
                .scope("clientes.write")
                .tokenSettings(tokenSettings)
                .build();

        // Canal movil: solo lectura en los tres dominios.
        RegisteredClient movil = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("banco-movil-client")
                .clientSecret(encoder.encode(secretoMovil))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("cuentas.read")
                .scope("movimientos.read")
                .scope("clientes.read")
                .tokenSettings(tokenSettings)
                .build();

        // Cajero automatico: consulta saldo y retira. Nada mas.
        RegisteredClient cajero = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("cajero-client")
                .clientSecret(encoder.encode(secretoCajero))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("cuentas.read")
                .scope("cuentas.write")
                .tokenSettings(tokenSettings)
                .build();

        // pagos-service llamando a cuentas-service: lee saldos y liquida las
        // operaciones que el mismo orquesta. No lleva cuentas.write: no necesita
        // poder pedir un retiro, necesita poder cargar y abonar.
        RegisteredClient servicioPagos = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("pagos-service")
                .clientSecret(encoder.encode(secretoPagos))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("cuentas.read")
                .scope("cuentas.liquidar")
                .tokenSettings(tokenSettings)
                .build();

        log.info(">> auth-server: clientes registrados = banco-web-client, banco-movil-client, "
                + "cajero-client, pagos-service");
        return new InMemoryRegisteredClientRepository(web, movil, cajero, servicioPagos);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Par de claves RSA con el que se firman los JWT. Se genera al arrancar: no
     * hay un keystore versionado en el repositorio, que es lo correcto para una
     * entrega academica pero tiene una consecuencia que conviene nombrar: al
     * reiniciar este servicio los tokens emitidos antes dejan de validar.
     * En produccion la clave vendria de un almacen externo y rotaria con
     * solapamiento entre claves.
     */
    @Bean
    JWKSource<SecurityContext> jwkSource() {
        KeyPair par = generarClaves();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) par.getPublic())
                .privateKey((RSAPrivateKey) par.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        log.info(">> auth-server: clave RSA de firma generada (kid={})", rsa.getKeyID());
        return new ImmutableJWKSet<>(new JWKSet(rsa));
    }

    private static KeyPair generarClaves() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar la clave de firma del auth-server", ex);
        }
    }
}
