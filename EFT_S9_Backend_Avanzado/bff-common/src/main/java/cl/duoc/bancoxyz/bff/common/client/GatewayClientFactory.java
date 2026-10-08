package cl.duoc.bancoxyz.bff.common.client;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Construye el cliente HTTP con el que un BFF cruza el api-gateway.
 *
 * Cambia respecto de la version de la semana 5 en dos cosas, y las dos son
 * consecuencia de haber metido los BFF dentro del ecosistema de microservicios:
 *
 * 1. El destino ya no son los servicios core uno por uno, sino el gateway. Un
 *    BFF no necesita saber en que puerto vive cuentas-service ni cuantas
 *    instancias hay; pide por la ruta publica y el gateway reparte entre las
 *    instancias que Eureka tenga registradas. Es lo que permite escalar un
 *    microservicio sin tocar ningun BFF.
 * 2. Cada peticion va firmada. El canal pide su propio token por
 *    client_credentials con las credenciales que le corresponden, y el gateway
 *    y el microservicio lo validan. Un BFF sin token recibe 401 igual que
 *    cualquier otro cliente: no hay trafico privilegiado por venir de dentro.
 *
 * Los timeouts siguen siendo explicitos por la misma razon que antes: un
 * servicio que acepta la conexion y no responde dejaria hilos del BFF
 * bloqueados hasta agotarlos.
 */
public final class GatewayClientFactory {

    private static final Duration TIMEOUT_CONEXION = Duration.ofSeconds(2);
    private static final Duration TIMEOUT_LECTURA = Duration.ofSeconds(5);

    private GatewayClientFactory() {
    }

    public static RestClient crear(String urlGateway,
                                   OAuth2AuthorizedClientManager gestor,
                                   String registro) {
        return RestClient.builder()
                .baseUrl(urlGateway)
                .requestFactory(fabricaConTimeouts())
                .requestInterceptor(interceptorToken(gestor, registro))
                .build();
    }

    static ClientHttpRequestFactory fabricaConTimeouts() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT_CONEXION)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIMEOUT_LECTURA);
        return factory;
    }

    private static ClientHttpRequestInterceptor interceptorToken(OAuth2AuthorizedClientManager gestor,
                                                                 String registro) {
        return (peticion, cuerpo, ejecucion) -> {
            OAuth2AuthorizedClient autorizado = gestor.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(registro)
                    .principal(registro)
                    .build());
            if (autorizado == null) {
                throw new IllegalStateException(
                        "El auth-server no entrego un token para el registro " + registro);
            }
            peticion.getHeaders().setBearerAuth(autorizado.getAccessToken().getTokenValue());
            return ejecucion.execute(peticion, cuerpo);
        };
    }
}
