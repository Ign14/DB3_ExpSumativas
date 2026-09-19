package cl.duoc.bancoxyz.batch.transacciones;

import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.transacciones.model.Transaccion;
import cl.duoc.bancoxyz.batch.transacciones.model.TransaccionCsv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransaccionItemProcessorTest {

    private final TransaccionItemProcessor processor = new TransaccionItemProcessor();

    @Test
    void deberia_procesar_una_transaccion_valida() throws Exception {
        TransaccionCsv csv = new TransaccionCsv();
        csv.setId("1");
        csv.setFecha("2024-06-30");
        csv.setMonto("3000");
        csv.setTipo("credito");

        Transaccion resultado = processor.process(csv);

        assertThat(resultado.getId()).isEqualTo(1L);
        assertThat(resultado.getMonto()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(resultado.getTipo()).isEqualTo("credito");
    }

    @Test
    void deberia_rechazar_monto_negativo_o_cero() {
        TransaccionCsv csv = new TransaccionCsv();
        csv.setId("2");
        csv.setFecha("2024-06-30");
        csv.setMonto("-200");
        csv.setTipo("credito");

        assertThatThrownBy(() -> processor.process(csv)).isInstanceOf(RegistroInvalidoException.class);
    }

    @Test
    void deberia_rechazar_tipo_fuera_de_dominio() {
        TransaccionCsv csv = new TransaccionCsv();
        csv.setId("3");
        csv.setFecha("2024-06-30");
        csv.setMonto("100");
        csv.setTipo("invalid");

        assertThatThrownBy(() -> processor.process(csv)).isInstanceOf(RegistroInvalidoException.class);
    }

    @Test
    void deberia_rechazar_fecha_no_interpretable() {
        TransaccionCsv csv = new TransaccionCsv();
        csv.setId("4");
        csv.setFecha("no-es-una-fecha");
        csv.setMonto("100");
        csv.setTipo("debito");

        assertThatThrownBy(() -> processor.process(csv)).isInstanceOf(RegistroInvalidoException.class);
    }
}
