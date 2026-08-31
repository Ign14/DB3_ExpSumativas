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
import java.time.LocalDateTime;

/**
 * Estado de cuenta anual compilado para auditoría (tabla
 * {@code estado_cuenta_anual}): totales por tipo de movimiento y saldo
 * neto del año para cada cuenta, generado por el Job 3.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EstadoCuentaAnual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cuentaId;
    private Integer anio;
    private BigDecimal totalDepositos;
    private BigDecimal totalRetiros;
    private BigDecimal totalCompras;
    private BigDecimal totalPagos;
    private BigDecimal saldoNeto;
    private long cantidadMovimientos;
    private LocalDateTime fechaGeneracion;
}
