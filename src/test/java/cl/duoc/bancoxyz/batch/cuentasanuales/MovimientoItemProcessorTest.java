package cl.duoc.bancoxyz.batch.cuentasanuales;

import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCsv;
import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCuentaAnual;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovimientoItemProcessorTest {

    private final MovimientoItemProcessor processor = new MovimientoItemProcessor();

    @Test
    void deberia_normalizar_tipos_de_movimiento_con_tildes_en_vez_de_descartarlos() throws Exception {
        MovimientoCsv csv = new MovimientoCsv();
        csv.setCuentaId("109");
        csv.setFecha("2024-03-18");
        csv.setTransaccion("depósito");
        csv.setMonto("3000");
        csv.setDescripcion("Ingreso mensual");

        MovimientoCuentaAnual resultado = processor.process(csv);

        assertThat(resultado.getTipoMovimiento()).isEqualTo("deposito");
    }

    @Test
    void deberia_completar_descripcion_vacia_con_valor_por_defecto() throws Exception {
        MovimientoCsv csv = new MovimientoCsv();
        csv.setCuentaId("110");
        csv.setFecha("24-07-2024");
        csv.setTransaccion("retiro");
        csv.setMonto("1500");
        csv.setDescripcion("");

        MovimientoCuentaAnual resultado = processor.process(csv);

        assertThat(resultado.getDescripcion()).isEqualTo("Sin descripción registrada");
    }

    @Test
    void deberia_rechazar_monto_negativo() {
        MovimientoCsv csv = new MovimientoCsv();
        csv.setCuentaId("111");
        csv.setFecha("2024-03-18");
        csv.setTransaccion("deposito");
        csv.setMonto("-100");
        csv.setDescripcion("Ingreso");

        assertThatThrownBy(() -> processor.process(csv)).isInstanceOf(RegistroInvalidoException.class);
    }
}
