package cl.duoc.bancoxyz.clientes.domain;

import cl.duoc.bancoxyz.common.dto.ClienteDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El padron de clientes sale del mismo archivo legacy que la migracion batch,
 * con los mismos errores sembrados: edades fuera de rango, tipos invalidos,
 * saldos vacios y nombres en blanco. Estas pruebas fijan que se descarta y que
 * se tolera, que no es lo mismo en los dos servicios que leen este archivo.
 */
class ClienteRepositoryEnMemoriaTest {

    private ClienteRepositoryEnMemoria repositorio;

    @BeforeEach
    void preparar() {
        repositorio = new ClienteRepositoryEnMemoria();
        repositorio.cargarDatos();
    }

    @Test
    @DisplayName("Carga clientes del archivo legacy descartando las filas invalidas")
    void cargaConValidacion() {
        assertTrue(repositorio.cantidadClientes() > 0, "deberia cargar al menos un cliente");
        List<ClienteDTO> algunos = repositorio.listar(200);
        assertFalse(algunos.isEmpty());
        for (ClienteDTO cliente : algunos) {
            assertTrue(cliente.edad() >= 18 && cliente.edad() <= 120,
                    "edad fuera de rango: " + cliente.edad());
            assertTrue(List.of("ahorro", "prestamo", "hipoteca").contains(cliente.tipoCuentaPrincipal()),
                    "tipo invalido: " + cliente.tipoCuentaPrincipal());
            assertTrue(cliente.saldoReferencial().signum() >= 0, "saldo negativo");
        }
    }

    @Test
    @DisplayName("Un saldo vacio no descarta al cliente: queda con saldo cero y segmento por producto")
    void saldoVacioNoDescartaAlCliente() {
        // A diferencia de cuentas-service, que descarta la fila porque el saldo
        // es la verdad del dinero, aqui el saldo es un dato comercial de
        // referencia. Un cliente sin saldo informado sigue siendo un cliente.
        List<ClienteDTO> conSaldoCero = repositorio.listar(200).stream()
                .filter(c -> c.saldoReferencial().signum() == 0)
                .toList();
        assertFalse(conSaldoCero.isEmpty(),
                "el archivo legacy trae filas con el saldo vacio: deberian seguir cargando");
        for (ClienteDTO cliente : conSaldoCero) {
            // El producto manda sobre el saldo: un hipotecario sin saldo
            // informado sigue siendo un hipotecario, porque lo que lo define es
            // el credito que tiene, no el dinero que no le registramos.
            String esperado = "hipoteca".equals(cliente.tipoCuentaPrincipal()) ? "hipotecario" : "basico";
            assertEquals(esperado, cliente.segmento(),
                    "cliente " + cliente.clienteId() + " con producto " + cliente.tipoCuentaPrincipal());
        }
    }

    @Test
    @DisplayName("El segmento se deriva del saldo y del producto, no se almacena")
    void segmentoDerivado() {
        assertEquals("hipotecario",
                new ClienteRegistro(1L, "X", 30, "hipoteca", new BigDecimal("9000")).segmento());
        assertEquals("preferente",
                new ClienteRegistro(1L, "X", 30, "ahorro", new BigDecimal("7000")).segmento());
        assertEquals("establecido",
                new ClienteRegistro(1L, "X", 30, "ahorro", new BigDecimal("4000")).segmento());
        assertEquals("basico",
                new ClienteRegistro(1L, "X", 30, "ahorro", new BigDecimal("3999")).segmento());
    }

    @Test
    @DisplayName("Un nombre vacio o 'Unknown' se reemplaza por un texto explicito")
    void nombresSinDato() {
        List<ClienteDTO> todos = repositorio.listar(200);
        assertTrue(todos.stream().noneMatch(c -> c.nombre().isBlank()));
        assertTrue(todos.stream().noneMatch(c -> "Unknown".equalsIgnoreCase(c.nombre())));
    }

    @Test
    @DisplayName("Renombrar cambia el titular y deja intacto el resto del perfil")
    void renombrar() {
        ClienteDTO antes = repositorio.listar(1).get(0);

        ClienteDTO despues = repositorio.renombrar(antes.clienteId(), "Nombre Corregido").orElseThrow();

        assertEquals("Nombre Corregido", despues.nombre());
        assertEquals(antes.edad(), despues.edad());
        assertEquals(antes.tipoCuentaPrincipal(), despues.tipoCuentaPrincipal());
        assertEquals(0, antes.saldoReferencial().compareTo(despues.saldoReferencial()));
    }

    @Test
    @DisplayName("Renombrar un cliente inexistente devuelve vacio y no lo crea")
    void renombrarInexistente() {
        int antes = repositorio.cantidadClientes();
        assertTrue(repositorio.renombrar(999999L, "Fantasma").isEmpty());
        assertEquals(antes, repositorio.cantidadClientes());
    }

    @Test
    @DisplayName("El listado respeta el limite pedido y viene ordenado por identificador")
    void listadoLimitadoYOrdenado() {
        List<ClienteDTO> cinco = repositorio.listar(5);
        assertEquals(5, cinco.size());
        for (int i = 1; i < cinco.size(); i++) {
            assertTrue(cinco.get(i - 1).clienteId() < cinco.get(i).clienteId(),
                    "el listado deberia venir ordenado");
        }
    }

    @Test
    @DisplayName("La actividad arranca en cero y se acumula por operacion y por alerta")
    void actividadSeAcumula() {
        Long cliente = repositorio.listar(1).get(0).clienteId();
        assertEquals(ActividadCliente.VACIA, repositorio.actividadDe(cliente));

        repositorio.registrarOperacion(cliente, "RETIRO de 500");
        repositorio.registrarOperacion(cliente, "DEPOSITO de 300");
        repositorio.registrarAlerta(cliente);

        ActividadCliente actividad = repositorio.actividadDe(cliente);
        assertEquals(2, actividad.operaciones());
        assertEquals(1, actividad.alertas());
        assertEquals("DEPOSITO de 300", actividad.ultimaOperacion(),
                "la ultima operacion deberia ser la mas reciente");
    }

    @Test
    @DisplayName("Un evento de una cuenta que no esta en el padron no se pierde")
    void actividadDeClienteDesconocido() {
        // Un evento habla de algo que ya paso. Descartarlo porque la cuenta no
        // figura en este archivo solo perderia informacion.
        repositorio.registrarOperacion(888888L, "DEPOSITO de 100");
        assertEquals(1, repositorio.actividadDe(888888L).operaciones());
    }

    @Test
    @DisplayName("Veinte hilos registrando operaciones del mismo cliente no pierden ninguna")
    void actividadBajoConcurrencia() throws Exception {
        // Los eventos de un topico con varias particiones llegan en hilos
        // distintos. Si leer, sumar y escribir no fuera una sola operacion
        // atomica, dos eventos simultaneos del mismo cliente perderian una
        // cuenta, y el contador quedaria silenciosamente corto.
        Long cliente = repositorio.listar(1).get(0).clienteId();
        int hilos = 20;
        int porHilo = 50;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch partida = new CountDownLatch(1);
        CountDownLatch llegada = new CountDownLatch(hilos);

        for (int i = 0; i < hilos; i++) {
            pool.submit(() -> {
                try {
                    partida.await();
                    for (int j = 0; j < porHilo; j++) {
                        repositorio.registrarOperacion(cliente, "DEPOSITO");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    llegada.countDown();
                }
            });
        }
        partida.countDown();
        assertTrue(llegada.await(20, TimeUnit.SECONDS), "los hilos deberian terminar");
        pool.shutdownNow();

        assertEquals(hilos * porHilo, repositorio.actividadDe(cliente).operaciones());
    }
}
