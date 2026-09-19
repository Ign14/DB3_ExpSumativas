package cl.duoc.bancoxyz.batch.transacciones.model;

import lombok.Data;

/**
 * Representa una fila cruda del archivo {@code transacciones.csv}
 * (columnas: id, fecha, monto, tipo), tal como llega del sistema legacy,
 * sin ningún tipo de validación todavía.
 */
@Data
public class TransaccionCsv {
    private String id;
    private String fecha;
    private String monto;
    private String tipo;
}
