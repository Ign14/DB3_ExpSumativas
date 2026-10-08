package cl.duoc.bancoxyz.clientes.mensajeria;

import cl.duoc.bancoxyz.clientes.domain.ClienteRepositoryEnMemoria;
import cl.duoc.bancoxyz.common.evento.AlertaSeguridadEvento;
import cl.duoc.bancoxyz.common.evento.TransaccionCompletadaEvento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El consumidor se prueba llamandolo directamente con el evento ya
 * deserializado, sin Kafka de por medio.
 *
 * Esa separacion es intencional: lo que hay que verificar aqui es la regla de
 * negocio —que una transferencia cuente en las dos cuentas y un retiro en una
 * sola—, y eso no necesita un broker. Que el evento viaje y se deserialice bien
 * es otra cosa, y se comprueba en la evidencia de ejecucion con el broker real.
 */
class ConsumidorEventosTest {

    private ClienteRepositoryEnMemoria repositorio;
    private ConsumidorEventos consumidor;

    @BeforeEach
    void preparar() {
        repositorio = new ClienteRepositoryEnMemoria();
        repositorio.cargarDatos();
        consumidor = new ConsumidorEventos(repositorio);
    }

    @Test
    @DisplayName("Un retiro cuenta como una operacion de la cuenta de origen")
    void retiroCuentaEnElOrigen() {
        consumidor.alCompletarseUnaTransaccion(new TransaccionCompletadaEvento(
                "evt-1", TransaccionCompletadaEvento.RETIRO, 101L, null,
                new BigDecimal("500"), new BigDecimal("6500"), "2026-10-08", "cajero"));

        assertEquals(1, repositorio.actividadDe(101L).operaciones());
    }

    @Test
    @DisplayName("Una transferencia cuenta en las dos cuentas: quien paga y quien recibe")
    void transferenciaCuentaEnLasDosCuentas() {
        consumidor.alCompletarseUnaTransaccion(new TransaccionCompletadaEvento(
                "evt-2", TransaccionCompletadaEvento.TRANSFERENCIA, 101L, 102L,
                new BigDecimal("500"), new BigDecimal("6000"), "2026-10-08", "web"));

        assertEquals(1, repositorio.actividadDe(101L).operaciones());
        assertEquals(1, repositorio.actividadDe(102L).operaciones(),
                "el titular que recibe el abono tambien tuvo actividad");
    }

    @Test
    @DisplayName("Un deposito con origen y destino iguales cuenta una sola vez")
    void depositoNoSeCuentaDosVeces() {
        // pagos-service publica el deposito con la misma cuenta en origen y
        // destino. Sumar por cada punta sin comparar las dejaria contando doble.
        consumidor.alCompletarseUnaTransaccion(new TransaccionCompletadaEvento(
                "evt-3", TransaccionCompletadaEvento.DEPOSITO, 101L, 101L,
                new BigDecimal("500"), new BigDecimal("7500"), "2026-10-08", "web"));

        assertEquals(1, repositorio.actividadDe(101L).operaciones());
    }

    @Test
    @DisplayName("Una alerta con cuenta suma al contador de alertas de esa cuenta")
    void alertaConCuenta() {
        consumidor.alLlegarUnaAlerta(new AlertaSeguridadEvento(
                "evt-4", AlertaSeguridadEvento.RETIRO_SOBRE_LIMITE, 101L,
                "Intento de retiro por 900000", AlertaSeguridadEvento.SEVERIDAD_MEDIA, "2026-10-08"));

        assertEquals(1, repositorio.actividadDe(101L).alertas());
        assertEquals(0, repositorio.actividadDe(101L).operaciones(),
                "una alerta no es una operacion");
    }

    @Test
    @DisplayName("Una alerta sin cuenta no se le atribuye a nadie")
    void alertaSinCuenta() {
        // Un circuito abierto es un problema de la dependencia, no de una
        // cuenta. El evento viene sin cuentaId justamente para que no se le
        // cargue a quien tuvo la mala suerte de pedir la ultima peticion.
        consumidor.alLlegarUnaAlerta(new AlertaSeguridadEvento(
                "evt-5", AlertaSeguridadEvento.DEPENDENCIA_DEGRADADA, null,
                "El circuito hacia cuentas se abrio", AlertaSeguridadEvento.SEVERIDAD_ALTA, "2026-10-08"));

        assertEquals(0, repositorio.actividadDe(101L).alertas());
    }

    @Test
    @DisplayName("La ultima operacion del perfil refleja el evento mas reciente")
    void ultimaOperacionSeActualiza() {
        consumidor.alCompletarseUnaTransaccion(new TransaccionCompletadaEvento(
                "evt-6", TransaccionCompletadaEvento.RETIRO, 101L, null,
                new BigDecimal("100"), new BigDecimal("6900"), "2026-10-07", "cajero"));
        consumidor.alCompletarseUnaTransaccion(new TransaccionCompletadaEvento(
                "evt-7", TransaccionCompletadaEvento.DEPOSITO, 101L, 101L,
                new BigDecimal("200"), new BigDecimal("7100"), "2026-10-08", "web"));

        assertEquals(2, repositorio.actividadDe(101L).operaciones());
        assertEquals("DEPOSITO de 200 el 2026-10-08", repositorio.actividadDe(101L).ultimaOperacion());
    }
}
