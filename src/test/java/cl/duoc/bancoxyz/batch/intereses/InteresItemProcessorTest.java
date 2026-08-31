package cl.duoc.bancoxyz.batch.intereses;

import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.intereses.model.InteresCsv;
import cl.duoc.bancoxyz.batch.intereses.model.InteresCuenta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteresItemProcessorTest {

    private final InteresItemProcessor processor = new InteresItemProcessor(
            new BigDecimal("0.005"), new BigDecimal("0.018"), new BigDecimal("0.012"));

    @Test
    void deberia_calcular_el_interes_de_una_cuenta_de_ahorro() throws Exception {
        InteresCsv csv = new InteresCsv();
        csv.setCuentaId("100");
        csv.setNombre("Jane Smith");
        csv.setSaldo("10000");
        csv.setEdad("40");
        csv.setTipo("ahorro");

        InteresCuenta resultado = processor.process(csv);

        assertThat(resultado.getInteresCalculado()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(resultado.getSaldoFinal()).isEqualByComparingTo(new BigDecimal("10050.00"));
    }

    @Test
    void deberia_rechazar_edad_fuera_de_rango_realista() {
        InteresCsv csv = new InteresCsv();
        csv.setCuentaId("101");
        csv.setSaldo("1000");
        csv.setEdad("150");
        csv.setTipo("ahorro");

        assertThatThrownBy(() -> processor.process(csv)).isInstanceOf(RegistroInvalidoException.class);
    }

    @Test
    void deberia_rechazar_tipo_de_cuenta_fuera_de_dominio() {
        InteresCsv csv = new InteresCsv();
        csv.setCuentaId("102");
        csv.setSaldo("1000");
        csv.setEdad("30");
        csv.setTipo("-1");

        assertThatThrownBy(() -> processor.process(csv)).isInstanceOf(RegistroInvalidoException.class);
    }

    @Test
    void deberia_rechazar_saldo_vacio() {
        InteresCsv csv = new InteresCsv();
        csv.setCuentaId("103");
        csv.setSaldo("");
        csv.setEdad("30");
        csv.setTipo("prestamo");

        assertThatThrownBy(() -> processor.process(csv)).isInstanceOf(RegistroInvalidoException.class);
    }
}
