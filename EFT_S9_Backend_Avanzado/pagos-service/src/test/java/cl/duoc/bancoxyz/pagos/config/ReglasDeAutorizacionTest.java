package cl.duoc.bancoxyz.pagos.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de las reglas de autorizacion de pagos-service.
 *
 * Mismo enfoque y mismas razones que el test homonimo de cuentas-service, donde
 * esta la explicacion completa.
 *
 * Lo que este servicio tiene de particular es que los endpoints de Actuator
 * quedan detras del token y no abiertos como la salud:
 * {@code /actuator/circuitbreakerevents} publica los mensajes de las
 * excepciones internas del servicio, que describen a que dependencia llama, con
 * que credenciales falla y en que estado esta el circuito.
 */
@SpringBootTest(classes = ReglasDeAutorizacionTest.AplicacionDePrueba.class)
@AutoConfigureMockMvc
class ReglasDeAutorizacionTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SeguridadConfig.class, ReglasDeAutorizacionTest.ControladorDeSonda.class})
    static class AplicacionDePrueba {
    }

    private static final String LECTURA = "SCOPE_movimientos.read";
    private static final String ESCRITURA = "SCOPE_movimientos.write";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token, nada del dominio responde")
    void sinTokenTodoEs401() throws Exception {
        mvc.perform(get("/movimientos/101")).andExpect(status().isUnauthorized());
        mvc.perform(get("/movimientos/101/ficha")).andExpect(status().isUnauthorized());
        mvc.perform(get("/transacciones-diarias/resumen")).andExpect(status().isUnauthorized());
        mvc.perform(post("/movimientos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/pagos/deposito/101")).andExpect(status().isUnauthorized());
        mvc.perform(post("/pagos/transferencia/101")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Consultar el historial, la ficha y el reporte diario exige movimientos.read")
    void consultarExigeLectura() throws Exception {
        for (String ruta : new String[]{"/movimientos/101", "/movimientos/101/resumen",
                "/movimientos/101/ficha", "/transacciones-diarias/resumen"}) {
            mvc.perform(get(ruta).with(jwt().authorities(() -> LECTURA)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Un token del dominio de cuentas no abre el de movimientos")
    void scopeDeOtroDominioNoSirve() throws Exception {
        // Es exactamente el caso del cajero: tiene cuentas.read y cuentas.write,
        // y por eso no puede leer el historial de nadie.
        mvc.perform(get("/movimientos/101").with(jwt().authorities(() -> "SCOPE_cuentas.read")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/movimientos/101").with(jwt().authorities(() -> "SCOPE_cuentas.write")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Depositar y transferir exige movimientos.write: leer no alcanza")
    void moverDineroExigeEscritura() throws Exception {
        for (String ruta : new String[]{"/pagos/deposito/101", "/pagos/transferencia/101", "/movimientos"}) {
            mvc.perform(post(ruta).with(jwt().authorities(() -> ESCRITURA)))
                    .andExpect(status().isOk());
            mvc.perform(post(ruta).with(jwt().authorities(() -> LECTURA)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Los endpoints de Resilience4j quedan detras del token, no abiertos")
    void actuatorDeResilienciaProtegido() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/circuitbreakerevents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/circuitbreakerevents").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una ruta no declarada queda denegada aunque el token sea valido")
    void rutaDesconocidaDenegada() throws Exception {
        mvc.perform(get("/pagos/historial").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/movimientos/101/ajuste").with(jwt().authorities(() -> ESCRITURA)))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ControladorDeSonda {

        @GetMapping("/movimientos/{cuentaId}")
        void historial(@PathVariable Long cuentaId) {
        }

        @GetMapping("/movimientos/{cuentaId}/resumen")
        void resumen(@PathVariable Long cuentaId) {
        }

        @GetMapping("/movimientos/{cuentaId}/ficha")
        void ficha(@PathVariable Long cuentaId) {
        }

        @GetMapping("/transacciones-diarias/resumen")
        void resumenDiario() {
        }

        @PostMapping("/movimientos")
        void registrar() {
        }

        @PostMapping("/pagos/deposito/{cuentaId}")
        void depositar(@PathVariable Long cuentaId) {
        }

        @PostMapping("/pagos/transferencia/{cuentaOrigen}")
        void transferir(@PathVariable Long cuentaOrigen) {
        }

        @GetMapping("/actuator/health")
        void salud() {
        }

        @GetMapping("/actuator/circuitbreakerevents")
        void eventosDelCircuito() {
        }

        @GetMapping("/pagos/historial")
        void rutaNoDeclarada() {
        }

        @PostMapping("/movimientos/{cuentaId}/ajuste")
        void otraRutaNoDeclarada(@PathVariable Long cuentaId) {
        }
    }
}
