package cl.duoc.bancoxyz.pagos.cliente;

import cl.duoc.bancoxyz.common.excepcion.LlamadaRechazadaException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica la clasificacion de respuestas contra un servidor HTTP de verdad.
 *
 * Es la pieza que decide que cuenta como fallo para el circuit breaker, asi que
 * probarla con un doble que devuelve el resultado ya clasificado no prueba nada:
 * hay que ver que un 404 real no lance y que un 500 real si lo haga. Se levanta
 * un servidor minimo del JDK en un puerto libre, sin dependencias de test
 * adicionales.
 */
class CuentasGatewayHttpTest {

    private HttpServer servidor;
    private CuentasGatewayHttp gateway;
    private final AtomicInteger estadoARespuesta = new AtomicInteger(200);
    private final AtomicReference<String> cuerpo = new AtomicReference<>(
            "{\"cuentaId\":103,\"nombreTitular\":\"Bob Johnson\",\"edad\":30,"
                    + "\"tipoCuenta\":\"ahorro\",\"saldo\":7000}");

    @BeforeEach
    void levantarServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/cuentas", intercambio -> {
            int estado = estadoARespuesta.get();
            byte[] salida = estado == 204 ? new byte[0] : cuerpo.get().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado, salida.length);
            try (OutputStream os = intercambio.getResponseBody()) {
                os.write(salida);
            }
        });
        servidor.start();

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
        fabrica.setReadTimeout(Duration.ofSeconds(1));
        gateway = new CuentasGatewayHttp(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort())
                .requestFactory(fabrica)
                .build());
    }

    @AfterEach
    void bajarServidor() {
        servidor.stop(0);
    }

    @Test
    @DisplayName("200 con cuerpo: se deserializa y el origen es el servicio")
    void respuestaCorrecta() {
        estadoARespuesta.set(200);

        ResultadoCuenta resultado = gateway.obtenerCuenta(103L);

        assertTrue(resultado.disponible());
        assertEquals(ResultadoCuenta.Origen.SERVICIO, resultado.origen());
        assertEquals("Bob Johnson", resultado.cuenta().nombreTitular());
    }

    /**
     * El caso que justifica la lista de record-exceptions del circuit breaker: un
     * 404 tiene que salir de aqui como un resultado, no como una excepcion. Si
     * lanzara, consultar cuentas inexistentes abriria el circuito y dejaria sin
     * datos a las consultas de cuentas que si existen.
     */
    @Test
    @DisplayName("404: no lanza, devuelve NO_ENCONTRADA")
    void cuentaInexistenteNoEsFallo() {
        estadoARespuesta.set(404);
        cuerpo.set("{\"mensaje\":\"La cuenta 999999 no existe.\"}");

        ResultadoCuenta resultado = gateway.obtenerCuenta(999_999L);

        assertEquals(ResultadoCuenta.Origen.NO_ENCONTRADA, resultado.origen());
        assertFalse(resultado.disponible());
    }

    @Test
    @DisplayName("500: es un fallo del servicio y lo registra el circuit breaker")
    void errorDelServidorEsFallo() {
        estadoARespuesta.set(500);
        cuerpo.set("{\"mensaje\":\"Error interno del servicio.\"}");

        ServicioNoDisponibleException ex = assertThrows(ServicioNoDisponibleException.class,
                () -> gateway.obtenerCuenta(103L));
        assertTrue(ex.getMessage().contains("500"));
    }

    /**
     * Un 401 significa que nuestro token no sirve. No es una caida del servicio y
     * no debe abrir el circuito: es configuracion, y la excepcion tiene que ser
     * distinta para que quede fuera de record-exceptions.
     */
    @Test
    @DisplayName("401 y 403: llamada rechazada, no servicio caido")
    void rechazoNoEsCaida() {
        estadoARespuesta.set(401);
        cuerpo.set("");
        LlamadaRechazadaException noAutorizado = assertThrows(LlamadaRechazadaException.class,
                () -> gateway.obtenerCuenta(103L));
        assertEquals(401, noAutorizado.codigoHttp());

        estadoARespuesta.set(403);
        LlamadaRechazadaException prohibido = assertThrows(LlamadaRechazadaException.class,
                () -> gateway.obtenerCuenta(103L));
        assertEquals(403, prohibido.codigoHttp());
    }

    @Test
    @DisplayName("Si no hay nadie escuchando, es servicio no disponible")
    void servicioCaidoEsFallo() {
        servidor.stop(0);

        assertThrows(ServicioNoDisponibleException.class, () -> gateway.obtenerCuenta(103L));
    }

    @Test
    @DisplayName("Un cuerpo que no se puede deserializar tambien es un fallo del servicio")
    void cuerpoInvalido() {
        estadoARespuesta.set(200);
        cuerpo.set("esto no es json");

        assertThrows(ServicioNoDisponibleException.class, () -> gateway.obtenerCuenta(103L));
    }
}
