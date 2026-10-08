package cl.duoc.bancoxyz.cuentas.config;

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
 * Pruebas de las reglas de autorizacion de cuentas-service.
 *
 * <h2>Por que existe este test</h2>
 *
 * El proyecto afirma, en el README y en el informe tecnico, que cada endpoint
 * exige un scope concreto y que el canal equivocado recibe 403 aunque su token
 * sea valido. Hasta aqui esa afirmacion solo se comprobaba ejecutando el
 * sistema completo y mirando codigos de respuesta en un log, es decir, a mano.
 *
 * Eso dejaba un hueco incomodo: alguien podia cambiar el scope que exige
 * {@code POST /cuentas/{id}/abono}, pasandolo de {@code cuentas.liquidar} a
 * {@code cuentas.write} (justamente la distincion que la documentacion presenta
 * como la decision de seguridad mas fina del sistema), y la suite completa
 * seguiria en verde. Una afirmacion de seguridad que ningun test sostiene es
 * una afirmacion sobre el pasado.
 *
 * <h2>Por que un controlador de sonda y no el real</h2>
 *
 * Lo que se prueba aqui es la cadena de filtros, no la logica de negocio. El
 * controlador real arrastraria el repositorio, el publicador JMS y el de Kafka,
 * y el test terminaria fallando por un broker ausente en vez de por una regla
 * mal escrita. La sonda declara exactamente las mismas rutas y metodos y
 * devuelve 200 vacio, de modo que un 403 solo puede venir de la autorizacion.
 *
 * La contrapartida honesta de esa eleccion: si alguien agrega un endpoint nuevo
 * al controlador real y no lo agrega aqui, este test no lo cubre. Por eso la
 * ultima prueba comprueba que cualquier ruta no declarada quede denegada, que
 * es la red que atrapa ese caso.
 */
@SpringBootTest(classes = ReglasDeAutorizacionTest.AplicacionDePrueba.class)
@AutoConfigureMockMvc
class ReglasDeAutorizacionTest {

    /**
     * Contexto minimo: la cadena de filtros que se quiere probar y un
     * controlador de sonda, nada mas.
     *
     * Se declara aqui en vez de arrancar {@code CuentasServiceApplication}
     * porque esa clase escanea el servicio completo y arrastraria el
     * repositorio, el publicador JMS y el de Kafka. El test terminaria fallando
     * por un {@code KafkaTemplate} ausente en vez de por una regla de
     * autorizacion mal escrita, que es justo lo contrario de lo que un test
     * tiene que decir cuando falla.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SeguridadConfig.class, ReglasDeAutorizacionTest.ControladorDeSonda.class})
    static class AplicacionDePrueba {
    }

    private static final String LECTURA = "SCOPE_cuentas.read";
    private static final String ESCRITURA = "SCOPE_cuentas.write";
    private static final String LIQUIDACION = "SCOPE_cuentas.liquidar";

    @Autowired
    private MockMvc mvc;

    // --- Sin token ---------------------------------------------------------

    @Test
    @DisplayName("Sin token, todo el dominio responde 401")
    void sinTokenTodoEs401() throws Exception {
        mvc.perform(get("/cuentas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/cuentas/101")).andExpect(status().isUnauthorized());
        mvc.perform(post("/cuentas/101/retiro")).andExpect(status().isUnauthorized());
        mvc.perform(post("/cuentas/101/abono")).andExpect(status().isUnauthorized());
        mvc.perform(post("/cuentas/101/cargo")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La salud queda abierta, porque es lo que consulta el healthcheck")
    void saludAbierta() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("El resto del actuator no queda abierto")
    void restoDelActuatorCerrado() throws Exception {
        // /actuator/env y /actuator/configprops publican configuracion y
        // property sources. Que la salud este abierta no puede arrastrar al
        // resto del actuator con ella.
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/configprops")).andExpect(status().isUnauthorized());
    }

    // --- Lectura -----------------------------------------------------------

    @Test
    @DisplayName("Consultar exige cuentas.read")
    void consultarExigeLectura() throws Exception {
        mvc.perform(get("/cuentas").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isOk());
        mvc.perform(get("/cuentas/101").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un token sin cuentas.read no puede consultar, aunque sea valido")
    void consultarSinLecturaEs403() throws Exception {
        mvc.perform(get("/cuentas/101").with(jwt().authorities(() -> ESCRITURA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/cuentas/101").with(jwt().authorities(() -> "SCOPE_movimientos.read")))
                .andExpect(status().isForbidden());
    }

    // --- Escritura ---------------------------------------------------------

    @Test
    @DisplayName("Retirar exige cuentas.write, y leer no alcanza")
    void retirarExigeEscritura() throws Exception {
        mvc.perform(post("/cuentas/101/retiro").with(jwt().authorities(() -> ESCRITURA)))
                .andExpect(status().isOk());
        mvc.perform(post("/cuentas/101/retiro").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isForbidden());
    }

    // --- Liquidacion: la distincion que importa ----------------------------

    @Test
    @DisplayName("Liquidar exige cuentas.liquidar: cuentas.write NO alcanza")
    void liquidarExigeSuPropioScope() throws Exception {
        // Esta es la prueba central del archivo. Un cajero tiene cuentas.write
        // porque puede retirar, y un retiro pasa por el limite por operacion y
        // deja el hecho en el topico. Mover saldo por la primitiva no hace
        // ninguna de las dos cosas, asi que no puede autorizarse con el mismo
        // scope. Si alguien relaja esta regla, este test lo detiene.
        for (String ruta : new String[]{"/cuentas/101/abono", "/cuentas/101/cargo"}) {
            mvc.perform(post(ruta).with(jwt().authorities(() -> LIQUIDACION)))
                    .andExpect(status().isOk());
            mvc.perform(post(ruta).with(jwt().authorities(() -> ESCRITURA)))
                    .andExpect(status().isForbidden());
            mvc.perform(post(ruta).with(jwt().authorities(() -> LECTURA)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Tener liquidar no habilita a retirar: los dos scopes son independientes")
    void liquidarNoHabilitaRetirar() throws Exception {
        mvc.perform(post("/cuentas/101/retiro").with(jwt().authorities(() -> LIQUIDACION)))
                .andExpect(status().isForbidden());
    }

    // --- La red de seguridad -----------------------------------------------

    @Test
    @DisplayName("Una ruta no declarada queda denegada aunque el token sea valido")
    void rutaDesconocidaDenegada() throws Exception {
        // anyRequest().denyAll() al final de la cadena. Es lo que hace que un
        // endpoint nuevo nazca cerrado en vez de nacer abierto: si alguien
        // agrega un controlador y olvida su regla, falla al llamarlo, que es
        // mucho mejor que quedar accesible sin que nadie lo note.
        mvc.perform(get("/cuentas-internas/101").with(jwt().authorities(() -> LECTURA)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/cuentas/101/ajuste").with(jwt().authorities(() -> ESCRITURA)))
                .andExpect(status().isForbidden());
    }

    /**
     * Declara las mismas rutas y metodos que CuentaController, sin su logica.
     * Un 200 significa "la cadena de filtros dejo pasar la peticion".
     */
    @RestController
    static class ControladorDeSonda {

        @GetMapping("/cuentas")
        void listar() {
        }

        @GetMapping("/cuentas/{cuentaId}")
        void obtener(@PathVariable Long cuentaId) {
        }

        @PostMapping("/cuentas/{cuentaId}/retiro")
        void retirar(@PathVariable Long cuentaId) {
        }

        @PostMapping("/cuentas/{cuentaId}/abono")
        void abonar(@PathVariable Long cuentaId) {
        }

        @PostMapping("/cuentas/{cuentaId}/cargo")
        void cargar(@PathVariable Long cuentaId) {
        }

        @GetMapping("/actuator/health")
        void salud() {
        }

        @GetMapping("/actuator/env")
        void entorno() {
        }

        @GetMapping("/actuator/configprops")
        void propiedades() {
        }

        @GetMapping("/cuentas-internas/{cuentaId}")
        void rutaNoDeclaradaEnLasReglas(@PathVariable Long cuentaId) {
        }

        @PostMapping("/cuentas/{cuentaId}/ajuste")
        void otraRutaNoDeclarada(@PathVariable Long cuentaId) {
        }
    }
}
