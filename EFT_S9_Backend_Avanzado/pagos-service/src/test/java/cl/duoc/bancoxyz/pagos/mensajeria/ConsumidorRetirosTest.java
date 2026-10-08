package cl.duoc.bancoxyz.pagos.mensajeria;

import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.evento.RetiroRealizadoEvento;
import cl.duoc.bancoxyz.pagos.domain.MovimientoRepositoryEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumidorRetirosTest {

    private MovimientoRepositoryEnMemoria repositorio;
    private ConsumidorRetiros consumidor;

    @BeforeEach
    void preparar() {
        // Repositorio vacio a proposito: lo que se mide es lo que agrega el
        // consumidor, no lo que venia del CSV.
        repositorio = new MovimientoRepositoryEnMemoria();
        consumidor = new ConsumidorRetiros(repositorio);
    }

    private RetiroRealizadoEvento evento(String id, BigDecimal monto, String fecha) {
        return new RetiroRealizadoEvento(id, 500L, monto, new BigDecimal("1000"), fecha, "cajero");
    }

    @Test
    @DisplayName("Un evento de retiro se convierte en un movimiento del historial")
    void eventoSeConvierteEnMovimiento() {
        consumidor.recibir(evento("evt-1", new BigDecimal("500"), "2024-06-01"));

        List<MovimientoDTO> movimientos = repositorio.obtenerMovimientos(500L);
        assertEquals(1, movimientos.size());
        MovimientoDTO movimiento = movimientos.get(0);
        assertEquals("retiro", movimiento.tipoMovimiento());
        assertEquals(0, new BigDecimal("500").compareTo(movimiento.monto()));
        assertEquals("2024-06-01", movimiento.fecha());
        assertTrue(movimiento.descripcion().contains("cajero"));
    }

    /**
     * JMS garantiza entrega "al menos una vez". Si el consumidor no fuera
     * idempotente, una reentrega duplicaria el retiro en el historial y el
     * resumen dejaria de cuadrar con el saldo.
     */
    @Test
    @DisplayName("La reentrega del mismo evento no duplica el movimiento")
    void reentregaNoDuplica() {
        RetiroRealizadoEvento repetido = evento("evt-1", new BigDecimal("500"), "2024-06-01");

        consumidor.recibir(repetido);
        consumidor.recibir(repetido);
        consumidor.recibir(repetido);

        assertEquals(1, repositorio.obtenerMovimientos(500L).size());
    }

    @Test
    @DisplayName("Dos retiros distintos del mismo monto si quedan los dos")
    void eventosDistintosNoSeConfunden() {
        consumidor.recibir(evento("evt-1", new BigDecimal("500"), "2024-06-01"));
        consumidor.recibir(evento("evt-2", new BigDecimal("500"), "2024-06-01"));

        assertEquals(2, repositorio.obtenerMovimientos(500L).size());
    }

    @Test
    @DisplayName("Un evento sin identificador o nulo se descarta sin romper el consumidor")
    void eventoSinIdentificador() {
        consumidor.recibir(null);
        consumidor.recibir(new RetiroRealizadoEvento(null, 500L, BigDecimal.TEN,
                BigDecimal.ONE, "2024-06-01", "web"));

        assertTrue(repositorio.obtenerMovimientos(500L).isEmpty());
    }

    /**
     * Un evento que no cumple las reglas del dominio no se vuelve a intentar: se
     * descarta y queda en el log. Reintentarlo seria un bucle infinito por un
     * dato que no va a mejorar. Pero el identificador se libera, para que una
     * reposicion manual corregida si pueda procesarse.
     */
    @Test
    @DisplayName("Un evento con datos invalidos se descarta y libera su identificador")
    void eventoInvalidoNoSeRegistra() {
        consumidor.recibir(evento("evt-malo", new BigDecimal("500"), "31/02/2024"));
        assertTrue(repositorio.obtenerMovimientos(500L).isEmpty());

        consumidor.recibir(evento("evt-malo", new BigDecimal("500"), "2024-06-01"));
        assertEquals(1, repositorio.obtenerMovimientos(500L).size());
    }
}
