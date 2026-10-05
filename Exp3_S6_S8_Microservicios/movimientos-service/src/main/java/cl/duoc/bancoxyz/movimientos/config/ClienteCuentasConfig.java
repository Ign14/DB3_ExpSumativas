package cl.duoc.bancoxyz.movimientos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * Cliente HTTP hacia cuentas-service.
 *
 * Tres cosas importan aqui. La primera es que la llamada va firmada: este
 * servicio pide su propio token por client_credentials, con el scope
 * cuentas.read y nada mas, en vez de reenviar el token del usuario final o
 * saltarse la seguridad por ser trafico interno.
 *
 * La segunda son los timeouts, y no solo los de la llamada de negocio. Sin
 * ellos, un servicio que acepta la conexion pero nunca responde deja hilos de
 * este servicio bloqueados hasta agotarlos, y el circuit breaker no alcanza a
 * protegernos porque nunca ve un fallo. El cliente que pide el token tambien los
 * necesita: Spring Security lo construye con un RestTemplate sin timeouts, asi
 * que un auth-server colgado bloquearia la llamada para siempre, antes incluso
 * de que la peticion a cuentas-service empiece.
 *
 * La tercera es el presupuesto de tiempo, que tiene que ser coherente entre
 * capas (ver el README): el peor caso del cliente HTTP tiene que quedar por
 * debajo del limite del TimeLimiter, o el TimeLimiter se convierte en el unico
 * freno y la peticion sigue viva despues de que el cliente se rindio.
 *
 *   conexion 1 s + lectura 1 s = 2 s por intento, con TimeLimiter en 2 s
 */
@Configuration
public class ClienteCuentasConfig {

    private static final Logger log = LoggerFactory.getLogger(ClienteCuentasConfig.class);

    /** Identificador del registro OAuth2 declarado en la configuracion central. */
    public static final String REGISTRO_CLIENTE = "cuentas-client";

    private static final Duration TIMEOUT_CONEXION = Duration.ofSeconds(1);
    private static final Duration TIMEOUT_LECTURA = Duration.ofSeconds(1);

    @Bean
    OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository registros) {
        return new InMemoryOAuth2AuthorizedClientService(registros);
    }

    /**
     * Gestor de tokens para trafico entre servicios. Se usa la variante
     * "AuthorizedClientService" y no la de servlet porque el consumidor de la
     * cola no corre dentro de una peticion HTTP: no hay sesion ni usuario
     * autenticado de donde colgar el token.
     *
     * El gestor cachea el token y lo renueva solo cuando expira, asi que no se
     * pide uno nuevo en cada llamada.
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registros,
                                                          OAuth2AuthorizedClientService servicio) {
        DefaultClientCredentialsTokenResponseClient clienteDeToken =
                new DefaultClientCredentialsTokenResponseClient();
        clienteDeToken.setRestOperations(plantillaParaElToken());

        AuthorizedClientServiceOAuth2AuthorizedClientManager gestor =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registros, servicio);
        gestor.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(paso -> paso.accessTokenResponseClient(clienteDeToken))
                .build());
        return gestor;
    }

    /**
     * Copia del RestTemplate que Spring Security usa para pedir el token, con
     * los mismos conversores y manejador de errores, pero con timeouts.
     */
    private RestTemplate plantillaParaElToken() {
        RestTemplate plantilla = new RestTemplate(Arrays.asList(
                new FormHttpMessageConverter(),
                new OAuth2AccessTokenResponseHttpMessageConverter()));
        plantilla.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        plantilla.setRequestFactory(fabricaConTimeouts());
        return plantilla;
    }

    private ClientHttpRequestFactory fabricaConTimeouts() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT_CONEXION)
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
        fabrica.setReadTimeout(TIMEOUT_LECTURA);
        return fabrica;
    }

    /**
     * Pool propio y acotado para las llamadas salientes a cuentas-service.
     *
     * Existe para que una dependencia colgada no agote el ForkJoinPool comun del
     * proceso (ver el javadoc de CuentasClienteResiliente). La cola es corta a
     * proposito: si se llena, la politica de descarte hace que la tarea se
     * ejecute en el hilo que llama, lo que frena al productor en vez de acumular
     * trabajo que ya no va a servirle a nadie.
     */
    @Bean("ejecutorCuentas")
    Executor ejecutorCuentas() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(8);
        pool.setMaxPoolSize(16);
        pool.setQueueCapacity(32);
        pool.setThreadNamePrefix("cuentas-");
        pool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        pool.initialize();
        return pool;
    }

    @Bean
    RestClient clienteCuentas(@Value("${banco.cuentas-service.url}") String urlBase,
                              OAuth2AuthorizedClientManager gestor) {
        log.info(">> movimientos-service: cliente HTTP hacia cuentas-service en {} "
                + "(conexion {} ms, lectura {} ms)", urlBase, TIMEOUT_CONEXION.toMillis(),
                TIMEOUT_LECTURA.toMillis());
        return RestClient.builder()
                .baseUrl(urlBase)
                .requestFactory(fabricaConTimeouts())
                .requestInterceptor(interceptorToken(gestor))
                .build();
    }

    private ClientHttpRequestInterceptor interceptorToken(OAuth2AuthorizedClientManager gestor) {
        return (peticion, cuerpo, ejecucion) -> {
            OAuth2AuthorizedClient autorizado = gestor.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(REGISTRO_CLIENTE)
                    .principal(REGISTRO_CLIENTE)
                    .build());
            if (autorizado == null) {
                throw new IllegalStateException(
                        "El auth-server no entrego un token para el registro " + REGISTRO_CLIENTE);
            }
            peticion.getHeaders().setBearerAuth(autorizado.getAccessToken().getTokenValue());
            return ejecucion.execute(peticion, cuerpo);
        };
    }
}
