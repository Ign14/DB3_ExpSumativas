package cl.duoc.bancoxyz.bff.common.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

/**
 * Gestor de tokens compartido por los tres BFF.
 *
 * Esta en el modulo comun y no repetido en cada canal porque la mecanica es
 * identica: lo que distingue a un canal de otro son sus credenciales y sus
 * scopes, que viven en la configuracion, no en el codigo.
 *
 * Se usa la variante basada en {@code AuthorizedClientService} y no la de
 * servlet porque el token no se obtiene en nombre de un usuario autenticado: el
 * BFF se autentica a si mismo como aplicacion. El gestor cachea el token y lo
 * renueva cuando expira, de modo que no se pide uno nuevo en cada llamada.
 *
 * El RestTemplate que pide el token lleva timeouts explicitos. Spring Security
 * lo construye sin ellos, asi que un auth-server colgado bloquearia la llamada
 * indefinidamente antes incluso de que la peticion al gateway empiece: el
 * timeout del cliente de negocio no cubre esa parte.
 */
@Configuration
public class OAuth2CanalConfig {

    @Bean
    OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository registros) {
        return new InMemoryOAuth2AuthorizedClientService(registros);
    }

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registros,
                                                          OAuth2AuthorizedClientService servicio) {
        DefaultClientCredentialsTokenResponseClient clienteDeToken =
                new DefaultClientCredentialsTokenResponseClient();
        RestTemplate plantilla = new RestTemplate(Arrays.asList(
                new FormHttpMessageConverter(),
                new org.springframework.security.oauth2.core.http.converter
                        .OAuth2AccessTokenResponseHttpMessageConverter()));
        plantilla.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        plantilla.setRequestFactory(GatewayClientFactory.fabricaConTimeouts());
        clienteDeToken.setRestOperations(plantilla);

        AuthorizedClientServiceOAuth2AuthorizedClientManager gestor =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registros, servicio);
        gestor.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(paso -> paso.accessTokenResponseClient(clienteDeToken))
                .build());
        return gestor;
    }
}
