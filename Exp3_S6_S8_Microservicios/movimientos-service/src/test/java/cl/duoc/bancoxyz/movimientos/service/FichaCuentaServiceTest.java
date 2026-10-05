package cl.duoc.bancoxyz.movimientos.service;

import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.FichaCuentaDTO;
import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.movimientos.cliente.CuentasClienteResiliente;
import cl.duoc.bancoxyz.movimientos.cliente.ResultadoCuenta;
import cl.duoc.bancoxyz.movimientos.domain.MovimientoRepositoryEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifica la decision que toma el servicio al agregar: que devolver cuando el
 * otro microservicio contesta, cuando contesta que no existe, y cuando no
 * contesta. Las tres situaciones se simulan sustituyendo el cliente resiliente
 * por un doble, sin red ni broker.
 *
 * Aqui se prueba la decision del servicio, no la resiliencia: el doble entrega el
 * {@link ResultadoCuenta} ya resuelto. Que el fallback produzca de verdad un
 * resultado DEGRADADO lo verifica
 * {@link cl.duoc.bancoxyz.movimientos.cliente.ResilienciaCableadaTest}, y que un
 * 404 real se traduzca a NO_ENCONTRADA,
 * {@link cl.duoc.bancoxyz.movimientos.cliente.CuentasGatewayHttpTest}.
 */
class FichaCuentaServiceTest {

    private static class ClienteFalso extends CuentasClienteResiliente {
        private final ResultadoCuenta respuesta;

        ClienteFalso(ResultadoCuenta respuesta) {
            super(cuentaId -> {
                throw new UnsupportedOperationException("el doble no debe llegar a la capa HTTP");
            }, Runnable::run);
            this.respuesta = respuesta;
        }

        @Override
        public CompletableFuture<ResultadoCuenta> obtenerCuenta(Long cuentaId) {
            return CompletableFuture.completedFuture(respuesta);
        }
    }

    private MovimientoRepositoryEnMemoria repositorio;

    @BeforeEach
    void preparar() {
        repositorio = new MovimientoRepositoryEnMemoria();
        repositorio.registrar(new MovimientoDTO(700L, "2024-04-01", "deposito", new BigDecimal("1000"), "Abono"));
    }

    @Test
    @DisplayName("Con cuentas-service arriba, la ficha trae los datos y se marca como SERVICIO")
    void fichaCompleta() {
        CuentaDTO cuenta = new CuentaDTO(700L, "Ana Perez", 34, "ahorro", new BigDecimal("5000"));
        FichaCuentaService servicio = new FichaCuentaService(
                new ClienteFalso(ResultadoCuenta.encontrada(cuenta)), repositorio);

        FichaCuentaDTO ficha = servicio.obtener(700L);

        assertEquals("SERVICIO", ficha.origenDatosCuenta());
        assertEquals("Ana Perez", ficha.cuenta().nombreTitular());
        assertEquals(1, ficha.resumen().totalMovimientos());
        assertEquals(1, ficha.movimientos().size());
    }

    /**
     * Esta es la razon de ser del fallback: con cuentas-service caido, quien
     * consulta movimientos recibe su historial igual. Lo unico que no puede
     * pasar es que lo reciba creyendo que los datos de cuenta son reales.
     */
    @Test
    @DisplayName("Con cuentas-service caido se entrega el historial marcado como DEGRADADO")
    void fichaDegradada() {
        FichaCuentaService servicio = new FichaCuentaService(
                new ClienteFalso(ResultadoCuenta.degradado()), repositorio);

        FichaCuentaDTO ficha = servicio.obtener(700L);

        assertEquals("DEGRADADO", ficha.origenDatosCuenta());
        assertNull(ficha.cuenta(), "no se inventan datos de cuenta");
        assertNotNull(ficha.resumen());
        assertEquals(1, ficha.movimientos().size(), "el historial propio se entrega igual");
    }

    @Test
    @DisplayName("Sin datos en ninguno de los dos servicios, la cuenta no existe")
    void cuentaInexistente() {
        FichaCuentaService servicio = new FichaCuentaService(
                new ClienteFalso(ResultadoCuenta.noEncontrada()), repositorio);

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.obtener(999_999L));
    }

    /**
     * Si cuentas-service no respondio, no se puede concluir que la cuenta no
     * exista: un 404 inventado seria una mentira sobre el estado del sistema.
     */
    @Test
    @DisplayName("Si la dependencia esta caida no se responde 404 aunque no haya historial")
    void degradadoSinHistorialNoEs404() {
        FichaCuentaService servicio = new FichaCuentaService(
                new ClienteFalso(ResultadoCuenta.degradado()), repositorio);

        FichaCuentaDTO ficha = servicio.obtener(999_999L);

        assertEquals("DEGRADADO", ficha.origenDatosCuenta());
        assertEquals(0, ficha.resumen().totalMovimientos());
    }

    @Test
    @DisplayName("Una cuenta que existe en cuentas-service pero no tiene movimientos devuelve ficha vacia")
    void cuentaSinMovimientos() {
        CuentaDTO cuenta = new CuentaDTO(701L, "Luis Soto", 40, "hipoteca", new BigDecimal("900"));
        FichaCuentaService servicio = new FichaCuentaService(
                new ClienteFalso(ResultadoCuenta.encontrada(cuenta)), repositorio);

        FichaCuentaDTO ficha = servicio.obtener(701L);

        assertEquals("SERVICIO", ficha.origenDatosCuenta());
        assertEquals(0, ficha.resumen().totalMovimientos());
        assertEquals(0, ficha.movimientos().size());
    }
}
