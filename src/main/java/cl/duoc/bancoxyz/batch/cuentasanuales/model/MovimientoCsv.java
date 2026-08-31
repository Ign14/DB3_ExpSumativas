package cl.duoc.bancoxyz.batch.cuentasanuales.model;

import lombok.Data;

/**
 * Fila cruda de {@code cuentas_anuales.csv}
 * (columnas: cuenta_id, fecha, transaccion, monto, descripcion).
 */
@Data
public class MovimientoCsv {
    private String cuentaId;
    private String fecha;
    private String transaccion;
    private String monto;
    private String descripcion;
}
