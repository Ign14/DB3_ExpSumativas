package cl.duoc.bancoxyz.bff.common.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Crea los clientes HTTP hacia los servicios core con timeouts explícitos, para
 * que un core que no responde haga fallar la llamada rápido en vez de dejar
 * hilos del BFF bloqueados.
 *
 * Se usa {@link JdkClientHttpRequestFactory} porque
 * {@code SimpleClientHttpRequestFactory} se apoya en {@code HttpURLConnection},
 * que no soporta PATCH (el método que usa el débito de saldo).
 */
public final class CoreRestClientFactory {

    private static final Duration TIMEOUT_CONEXION = Duration.ofSeconds(2);
    private static final Duration TIMEOUT_LECTURA = Duration.ofSeconds(5);

    private CoreRestClientFactory() {
    }

    public static RestClient crear(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT_CONEXION)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIMEOUT_LECTURA);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
