package cl.duoc.bancoxyz.cuentas.service;

import cl.duoc.bancoxyz.common.dto.RetiroRequest;
import cl.duoc.bancoxyz.common.dto.RetiroResponse;
import cl.duoc.bancoxyz.common.evento.RetiroRealizadoEvento;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.cuentas.domain.CuentaRepositoryEnMemoria;
import cl.duoc.bancoxyz.cuentas.mensajeria.PublicadorRetiros;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los dobles de prueba estan escritos a mano en vez de generarse con una
 * libreria de mocks. La razon es concreta: las librerias de mocks instrumentan
 * bytecode y se rompen al cambiar de version del JDK, y este proyecto tiene que
 * compilar igual en el JDK con el que se desarrollo y en el del equipo donde se
 * revisa. Una subclase de tres lineas no tiene ese problema.
 */
class RetiroServiceTest {

    /** Publicador que registra lo que se le pide y puede simular una caida del broker. */
    private static class PublicadorEspia extends PublicadorRetiros {
        private final List<RetiroRealizadoEvento> publicados = new ArrayList<>();
        private boolean brokerCaido;

        PublicadorEspia() {
            super(null);
        }

        @Override
        public boolean publicar(RetiroRealizadoEvento evento) {
            if (brokerCaido) {
                return false;
            }
            publicados.add(evento);
            return true;
        }
    }

    private CuentaRepositoryEnMemoria repositorio;
    private PublicadorEspia publicador;
    private RetiroService servicio;
    private Long cuentaConSaldo;

    @BeforeEach
    void preparar() {
        repositorio = new CuentaRepositoryEnMemoria();
        repositorio.cargarDatos();
        publicador = new PublicadorEspia();
        servicio = new RetiroService(repositorio, publicador, new BigDecimal("500000"));
        cuentaConSaldo = repositorio.listar().stream()
                .filter(c -> c.saldo().compareTo(new BigDecimal("1000")) >= 0)
                .findFirst().orElseThrow().cuentaId();
    }

    @Test
    @DisplayName("Un retiro valido se aprueba y publica exactamente un evento")
    void retiroAprobadoPublicaEvento() {
        RetiroResponse respuesta = servicio.retirar(cuentaConSaldo, new RetiroRequest(new BigDecimal("500"), "web"));

        assertTrue(respuesta.aprobado());
        assertTrue(respuesta.eventoPublicado());
        assertNotNull(respuesta.eventoId());
        assertEquals(1, publicador.publicados.size());

        RetiroRealizadoEvento evento = publicador.publicados.get(0);
        assertEquals(respuesta.eventoId(), evento.eventoId());
        assertEquals(cuentaConSaldo, evento.cuentaId());
        assertEquals(0, new BigDecimal("500").compareTo(evento.monto()));
        assertEquals(0, respuesta.saldoResultante().compareTo(evento.saldoResultante()));
        assertEquals("web", evento.canal());
    }

    @Test
    @DisplayName("Si el broker no acepta el evento, el retiro sigue aprobado y se informa")
    void brokerCaidoNoRevierteElRetiro() {
        BigDecimal saldoInicial = repositorio.buscar(cuentaConSaldo).orElseThrow().saldo();
        publicador.brokerCaido = true;

        RetiroResponse respuesta = servicio.retirar(cuentaConSaldo, new RetiroRequest(new BigDecimal("500"), "cajero"));

        assertTrue(respuesta.aprobado(), "el dinero ya salio: el retiro no se revierte");
        assertFalse(respuesta.eventoPublicado(), "el cliente debe poder notar que el evento no se publico");
        assertEquals(0, saldoInicial.subtract(new BigDecimal("500"))
                .compareTo(repositorio.buscar(cuentaConSaldo).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("Un retiro sobre el limite por operacion se rechaza sin tocar el saldo")
    void rechazaPorLimiteDelNegocio() {
        BigDecimal saldoInicial = repositorio.buscar(cuentaConSaldo).orElseThrow().saldo();

        RetiroResponse respuesta = servicio.retirar(cuentaConSaldo,
                new RetiroRequest(new BigDecimal("500001"), "web"));

        assertFalse(respuesta.aprobado());
        assertTrue(respuesta.motivoRechazo().contains("limite"));
        assertTrue(publicador.publicados.isEmpty(), "un retiro rechazado no debe generar evento");
        assertEquals(0, saldoInicial.compareTo(repositorio.buscar(cuentaConSaldo).orElseThrow().saldo()));
    }

    @Test
    @DisplayName("El limite se lee de la configuracion, no esta fijo en el codigo")
    void limiteConfigurable() {
        RetiroService servicioConLimiteBajo = new RetiroService(repositorio, publicador, new BigDecimal("100"));

        RetiroResponse respuesta = servicioConLimiteBajo.retirar(cuentaConSaldo,
                new RetiroRequest(new BigDecimal("500"), "web"));

        assertFalse(respuesta.aprobado());
        assertTrue(respuesta.motivoRechazo().contains("100"));
    }

    @Test
    @DisplayName("Un retiro sin fondos se rechaza y tampoco genera evento")
    void rechazaPorFondos() {
        BigDecimal saldo = repositorio.buscar(cuentaConSaldo).orElseThrow().saldo();

        RetiroResponse respuesta = servicio.retirar(cuentaConSaldo,
                new RetiroRequest(saldo.add(BigDecimal.ONE), "web"));

        assertFalse(respuesta.aprobado());
        assertEquals("Fondos insuficientes", respuesta.motivoRechazo());
        assertTrue(publicador.publicados.isEmpty());
    }

    @Test
    @DisplayName("Monto ausente, cero o negativo es un error del cliente, no un rechazo de negocio")
    void montosInvalidos() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.retirar(cuentaConSaldo, new RetiroRequest(null, "web")));
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.retirar(cuentaConSaldo, new RetiroRequest(BigDecimal.ZERO, "web")));
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.retirar(cuentaConSaldo, new RetiroRequest(new BigDecimal("-100"), "web")));
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.retirar(cuentaConSaldo, null));
    }

    @Test
    @DisplayName("Retirar de una cuenta inexistente responde 404 y no publica nada")
    void cuentaInexistente() {
        assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.retirar(999_999L, new RetiroRequest(new BigDecimal("100"), "web")));
        assertTrue(publicador.publicados.isEmpty());
    }

    @Test
    @DisplayName("Un retiro sin canal informado queda marcado como tal en el evento")
    void canalAusente() {
        servicio.retirar(cuentaConSaldo, new RetiroRequest(new BigDecimal("100"), "  "));
        assertEquals("no-informado", publicador.publicados.get(0).canal());
    }
}
