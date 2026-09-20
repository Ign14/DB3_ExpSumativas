package cl.duoc.bancoxyz.bff.common.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Construye los {@link RestClient} hacia los servicios core con timeouts
 * explicitos.
 *
 * <p>Sin timeout, un servicio core colgado (no caido: colgado) deja hilos
 * del BFF bloqueados indefinidamente y termina agotando el pool de Tomcat,
 * que es una forma silenciosa de consumir recursos. Con timeout, la llamada
 * falla rapido y el BFF responde 502 en vez de quedarse esperando.</p>
 *
 * <p>Se usa {@link JdkClientHttpRequestFactory} (sobre
 * {@link java.net.http.HttpClient}) y no {@code SimpleClientHttpRequestFactory}:
 * este ultimo se apoya en {@code HttpURLConnection}, que no soporta el
 * metodo PATCH y hace fallar el debito de saldo del canal cajero.</p>
 */
public final class CoreRestClientFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private CoreRestClientFactory() {
    }

    public static RestClient crear(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
