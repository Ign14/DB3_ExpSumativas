package cl.duoc.bancoxyz.batch.transacciones.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transacción diaria ya validada y lista para el reporte/auditoría
 * (tabla {@code transaccion}).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transaccion {

    @Id
    private Long id;

    private LocalDate fecha;
    private BigDecimal monto;
    private String tipo;
    private LocalDateTime fechaProceso;
}
