package cl.duoc.bancoxyz.batch.intereses;

import cl.duoc.bancoxyz.batch.common.exception.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.intereses.model.InteresCsv;
import cl.duoc.bancoxyz.batch.intereses.model.InteresCuenta;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Valida cada fila de {@code intereses.csv} y aplica la tasa de interés
 * mensual correspondiente al tipo de cuenta.
 * <p>
 * Reglas de consistencia (según los problemas simulados en el dataset:
 * edades no válidas, saldos vacíos, tipos fuera de dominio como {@code -1}
 * o {@code unknown}):
 * <ul>
 *   <li>saldo: obligatorio y mayor o igual a 0.</li>
 *   <li>edad: obligatoria y dentro de un rango realista [18, 120].</li>
 *   <li>tipo: debe ser {@code ahorro}, {@code prestamo} o {@code hipoteca}.</li>
 * </ul>
 * Regla de negocio (supuesto documentado en el README/propuesta técnica,
 * ya que el enunciado no fija tasas concretas): en cuentas de
 * <b>ahorro</b> el interés se abona a favor del cliente (el saldo sube);
 * en <b>préstamos</b> e <b>hipotecas</b> el interés se carga sobre la
 * deuda (el saldo también sube, mismo cálculo, pero representa lo que el
 * cliente pasa a deber). Las tasas se configuran en application.yml
 * (batch.intereses.*) para no dejarlas fijas en el código.
 */
public class InteresItemProcessor implements ItemProcessor<InteresCsv, InteresCuenta> {

    private static final int EDAD_MINIMA = 18;
    private static final int EDAD_MAXIMA = 120;

    private final Map<String, BigDecimal> tasasPorTipo;

    public InteresItemProcessor(BigDecimal tasaAhorro, BigDecimal tasaPrestamo, BigDecimal tasaHipoteca) {
        this.tasasPorTipo = Map.of(
                "ahorro", tasaAhorro,
                "prestamo", tasaPrestamo,
                "hipoteca", tasaHipoteca);
    }

    @Override
    public InteresCuenta process(InteresCsv item) {
        Long cuentaId = parseLong(item.getCuentaId());

        BigDecimal saldo = parseDecimal(item.getSaldo());
        if (saldo == null || saldo.compareTo(BigDecimal.ZERO) < 0) {
            throw new RegistroInvalidoException(
                    "cuenta_id=" + item.getCuentaId() + " saldo inválido: '" + item.getSaldo() + "'");
        }

        Integer edad = parseInt(item.getEdad());
        if (edad == null || edad < EDAD_MINIMA || edad > EDAD_MAXIMA) {
            throw new RegistroInvalidoException(
                    "cuenta_id=" + item.getCuentaId() + " edad fuera de rango: '" + item.getEdad() + "'");
        }

        String tipo = item.getTipo() == null ? "" : item.getTipo().trim().toLowerCase();
        BigDecimal tasa = tasasPorTipo.get(tipo);
        if (tasa == null) {
            throw new RegistroInvalidoException(
                    "cuenta_id=" + item.getCuentaId() + " tipo de cuenta fuera de dominio: '" + item.getTipo() + "'");
        }

        String nombre = (item.getNombre() == null || item.getNombre().isBlank()) ? "SIN_NOMBRE" : item.getNombre().trim();

        BigDecimal interes = saldo.multiply(tasa).setScale(2, RoundingMode.HALF_UP);
        BigDecimal saldoFinal = saldo.add(interes).setScale(2, RoundingMode.HALF_UP);

        return new InteresCuenta(null, cuentaId, nombre, tipo, saldo, tasa, interes, saldoFinal, LocalDateTime.now());
    }

    private Long parseLong(String valor) {
        try {
            return Long.valueOf(valor.trim());
        } catch (Exception e) {
            throw new RegistroInvalidoException("cuenta_id no numérico: '" + valor + "'");
        }
    }

    private BigDecimal parseDecimal(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
