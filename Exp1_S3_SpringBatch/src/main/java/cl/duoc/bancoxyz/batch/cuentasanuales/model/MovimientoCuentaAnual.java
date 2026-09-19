package cl.duoc.bancoxyz.batch.cuentasanuales.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Movimiento individual ya validado de una cuenta, listo para compilarse en
 * el estado de cuenta anual (tabla {@code movimiento_cuenta_anual}).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovimientoCuentaAnual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cuentaId;
    private LocalDate fecha;
    private Integer anio;
    private String tipoMovimiento;
    private BigDecimal monto;
    private String descripcion;
    private LocalDateTime fechaProceso;
}
