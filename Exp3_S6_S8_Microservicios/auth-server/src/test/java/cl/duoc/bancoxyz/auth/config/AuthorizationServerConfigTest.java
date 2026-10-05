package cl.duoc.bancoxyz.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El minimo privilegio por cliente es una afirmacion de seguridad, asi que
 * conviene que haya un test que falle si alguien la amplia sin darse cuenta.
 */
class AuthorizationServerConfigTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final RegisteredClientRepository repositorio =
            new AuthorizationServerConfig().registeredClientRepository(
                    encoder, "banco-web-secret", "cajero-secret", "movimientos-secret");

    private RegisteredClient cliente(String clientId) {
        RegisteredClient cliente = repositorio.findByClientId(clientId);
        assertNotNull(cliente, "deberia existir el cliente " + clientId);
        return cliente;
    }

    @Test
    @DisplayName("El cajero puede consultar y retirar, pero no leer el historial de movimientos")
    void cajeroConPrivilegioMinimo() {
        assertEquals(Set.of("cuentas.read", "cuentas.write"), cliente("cajero-client").getScopes());
    }

    @Test
    @DisplayName("movimientos-service solo puede leer cuentas, nunca escribirlas")
    void servicioInternoSoloLectura() {
        assertEquals(Set.of("cuentas.read"), cliente("movimientos-service").getScopes());
    }

    @Test
    @DisplayName("El canal web es el unico con acceso a los cuatro scopes")
    void canalWebCompleto() {
        assertEquals(Set.of("cuentas.read", "cuentas.write", "movimientos.read", "movimientos.write"),
                cliente("banco-web-client").getScopes());
    }

    @Test
    @DisplayName("Todos los clientes usan client_credentials y ninguno otro flujo")
    void soloClientCredentials() {
        for (String id : Set.of("banco-web-client", "cajero-client", "movimientos-service")) {
            Set<AuthorizationGrantType> flujos = cliente(id).getAuthorizationGrantTypes();
            assertEquals(Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS), flujos,
                    "el cliente " + id + " no deberia tener otros flujos habilitados");
        }
    }

    @Test
    @DisplayName("Los secretos quedan cifrados, no en texto plano")
    void secretosCifrados() {
        RegisteredClient web = cliente("banco-web-client");
        assertNotEquals("banco-web-secret", web.getClientSecret());
        assertTrue(encoder.matches("banco-web-secret", web.getClientSecret()));
        assertFalse(encoder.matches("otro-secreto", web.getClientSecret()));
    }

    @Test
    @DisplayName("El token de acceso expira: no se emiten credenciales eternas")
    void tokenConVencimiento() {
        assertFalse(cliente("banco-web-client").getTokenSettings().getAccessTokenTimeToLive().isZero());
        assertTrue(cliente("banco-web-client").getTokenSettings()
                .getAccessTokenTimeToLive().toMinutes() <= 60);
    }
}
