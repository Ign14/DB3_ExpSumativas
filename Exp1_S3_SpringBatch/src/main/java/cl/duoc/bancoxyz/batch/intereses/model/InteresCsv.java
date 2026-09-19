package cl.duoc.bancoxyz.batch.intereses.model;

import lombok.Data;

/**
 * Fila cruda de {@code intereses.csv}
 * (columnas: cuenta_id, nombre, saldo, edad, tipo).
 */
@Data
public class InteresCsv {
    private String cuentaId;
    private String nombre;
    private String saldo;
    private String edad;
    private String tipo;
}
