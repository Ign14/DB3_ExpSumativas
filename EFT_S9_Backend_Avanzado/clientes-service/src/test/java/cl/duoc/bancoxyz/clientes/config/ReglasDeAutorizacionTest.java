package cl.duoc.bancoxyz.clientes.config;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de las reglas de autorizacion de clientes-service.
 *
 * Mismo enfoque y mismas razones que el test homonimo de cuentas-service, donde
 * esta la explicacion completa: contexto minimo, controlador de sonda en vez
 * del real, y una prueba final que comprueba que lo no declarado queda
 * denegado.
 *
 * La separacion entre lectura y escritura importa especialmente en este
 * servicio, porque es el unico que guarda datos personales: el canal movil
 * puede leer el perfil para mostrarlo, pero solo la consola web puede
 * modificarlo.
 */
@SpringBootTest(classes = ReglasDeAutorizacionTest.AplicacionDePrueba.class)
@AutoConfigureMockMvc
class ReglasDeAutorizacionTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SeguridadConfig.class, ReglasDeAutorizacionTest.ControladorDeSonda.class})
    static class AplicacionDePrueba {
    }

    private static final String LECTURA = "SCOPE_clientes.read";
    private static final String ESCRITURA = "SCOPE_clientes.write";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token, los datos personales no se leen ni se escriben")
    void sinTokenTodoEs401() throws Exception {
        mvc.perform(get("/clientes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/clientes/101")).andExpect(status().isUnauthorized());
        mvc.perform(put("/clientes/101/nombre")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La salud queda abierta y el resto del actuator no")
    void actuator() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Leer el perfil exige clientes.read")
    void leerExigeLectura() throws Exception {
        mvc.perform(get("/clientes/101").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isOk());
        mvc.perform(get("/clientes").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un token de otro dominio no abre este: los scopes no son intercambiables")
    void scopeDeOtroDominioNoSirve() throws Exception {
        mvc.perform(get("/clientes/101").with(jwt().authorities(() -> "SCOPE_cuentas.read")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/clientes/101").with(jwt().authorities(() -> "SCOPE_movimientos.read")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Editar datos personales exige clientes.write: leer no alcanza")
    void escribirExigeEscritura() throws Exception {
        // Es la regla que deja al canal movil sin poder editar el nombre del
        // titular aunque pueda mostrarlo. Si alguien relajara esto a
        // clientes.read, una aplicacion de solo lectura pasaria a poder
        // modificar datos personales.
        mvc.perform(put("/clientes/101/nombre").with(jwt().authorities(() -> ESCRITURA)))
                .andExpect(status().isOk());
        mvc.perform(put("/clientes/101/nombre").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Una ruta no declarada queda denegada aunque el token sea valido")
    void rutaDesconocidaDenegada() throws Exception {
        mvc.perform(get("/clientes-internos/101").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/clientes/101/segmento").with(jwt().authorities(() -> ESCRITURA)))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ControladorDeSonda {

        @GetMapping("/clientes")
        void listar() {
        }

        @GetMapping("/clientes/{clienteId}")
        void obtener(@PathVariable Long clienteId) {
        }

        @PutMapping("/clientes/{clienteId}/nombre")
        void renombrar(@PathVariable Long clienteId) {
        }

        @GetMapping("/actuator/health")
        void salud() {
        }

        @GetMapping("/actuator/env")
        void entorno() {
        }

        @GetMapping("/clientes-internos/{clienteId}")
        void rutaNoDeclarada(@PathVariable Long clienteId) {
        }

        @PutMapping("/clientes/{clienteId}/segmento")
        void otraRutaNoDeclarada(@PathVariable Long clienteId) {
        }
    }
}
