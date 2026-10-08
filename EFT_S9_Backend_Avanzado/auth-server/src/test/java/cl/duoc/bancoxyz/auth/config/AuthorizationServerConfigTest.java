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
 * El minimo privilegio por canal es una afirmacion de seguridad, asi que
 * conviene que haya tests que fallen si alguien la amplia sin darse cuenta.
 *
 * Estos tests estan escritos con igualdad exacta de conjuntos y no con
 * "contiene". La diferencia es el objetivo: un test que comprueba que el cajero
 * contiene cuentas.write sigue pasando el dia en que alguien le agregue
 * clientes.read, y ese es precisamente el cambio que deberia hacer ruido.
 */
class AuthorizationServerConfigTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final RegisteredClientRepository repositorio =
            new AuthorizationServerConfig().registeredClientRepository(
                    encoder, "banco-web-secret", "banco-movil-secret", "cajero-secret", "pagos-secret");

    private RegisteredClient cliente(String clientId) {
        RegisteredClient cliente = repositorio.findByClientId(clientId);
        assertNotNull(cliente, "deberia existir el cliente " + clientId);
        return cliente;
    }

    @Test
    @DisplayName("El cajero puede consultar y retirar, pero no leer movimientos ni datos de clientes")
    void cajeroConPrivilegioMinimo() {
        assertEquals(Set.of("cuentas.read", "cuentas.write"), cliente("cajero-client").getScopes());
    }

    @Test
    @DisplayName("El canal movil solo lee: no mueve dinero ni edita datos personales")
    void canalMovilSoloLectura() {
        Set<String> scopes = cliente("banco-movil-client").getScopes();
        assertEquals(Set.of("cuentas.read", "movimientos.read", "clientes.read"), scopes);
        assertTrue(scopes.stream().noneMatch(s -> s.endsWith(".write")),
                "el canal movil no deberia tener ningun scope de escritura");
    }

    @Test
    @DisplayName("El canal web es el unico con los seis scopes del sistema")
    void canalWebCompleto() {
        assertEquals(Set.of("cuentas.read", "cuentas.write",
                        "movimientos.read", "movimientos.write",
                        "clientes.read", "clientes.write"),
                cliente("banco-web-client").getScopes());
    }

    @Test
    @DisplayName("pagos-service liquida saldo, pero no pide retiros ni toca datos de clientes")
    void servicioInternoAcotadoASuDominio() {
        Set<String> scopes = cliente("pagos-service").getScopes();
        assertEquals(Set.of("cuentas.read", "cuentas.liquidar"), scopes);
        assertTrue(scopes.stream().noneMatch(s -> s.startsWith("clientes.")),
                "pagos-service no necesita datos personales para mover dinero");
    }

    @Test
    @DisplayName("Solo pagos-service puede liquidar: ningun canal mueve saldo sin dejar el hecho en el topico")
    void liquidacionSoloDesdeElServicioDuenoDeLaOperacion() {
        for (String id : Set.of("banco-web-client", "banco-movil-client", "cajero-client")) {
            assertFalse(cliente(id).getScopes().contains("cuentas.liquidar"),
                    "el canal " + id + " no deberia poder liquidar saldo directamente");
        }
        assertTrue(cliente("pagos-service").getScopes().contains("cuentas.liquidar"));
    }

    @Test
    @DisplayName("Ningun cliente, salvo el canal web, puede escribir datos personales")
    void escrituraDeClientesSoloDesdeLaConsolaWeb() {
        for (String id : Set.of("banco-movil-client", "cajero-client", "pagos-service")) {
            assertFalse(cliente(id).getScopes().contains("clientes.write"),
                    "el cliente " + id + " no deberia poder editar datos personales");
        }
        assertTrue(cliente("banco-web-client").getScopes().contains("clientes.write"));
    }

    @Test
    @DisplayName("Todos los clientes usan client_credentials y ningun otro flujo")
    void soloClientCredentials() {
        for (String id : Set.of("banco-web-client", "banco-movil-client", "cajero-client", "pagos-service")) {
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
    @DisplayName("Cada canal tiene su propio secreto: uno filtrado no sirve para otro")
    void secretosDistintosPorCanal() {
        assertFalse(encoder.matches("cajero-secret", cliente("banco-web-client").getClientSecret()));
        assertFalse(encoder.matches("banco-web-secret", cliente("cajero-client").getClientSecret()));
        assertFalse(encoder.matches("banco-movil-secret", cliente("pagos-service").getClientSecret()));
    }

    @Test
    @DisplayName("El token de acceso expira: no se emiten credenciales eternas")
    void tokenConVencimiento() {
        for (String id : Set.of("banco-web-client", "banco-movil-client", "cajero-client", "pagos-service")) {
            assertFalse(cliente(id).getTokenSettings().getAccessTokenTimeToLive().isZero());
            assertTrue(cliente(id).getTokenSettings().getAccessTokenTimeToLive().toMinutes() <= 60,
                    "el token de " + id + " no deberia durar mas de una hora");
        }
    }
}
