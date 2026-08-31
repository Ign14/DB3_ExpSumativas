package cl.duoc.bancoxyz.batch.transacciones.model;

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
 * Resumen agregado del "Reporte de Transacciones Diarias" exigido por el
 * enunciado: totales de créditos/débitos, anomalías detectadas y monto neto
 * del período procesado. Se genera al final del Job mediante un Tasklet de
 * agregación.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResumenTransaccionesDiarias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime fechaGeneracion;
    private long totalProcesadas;
    private long totalCreditos;
    private long totalDebitos;
    private long totalAnomalias;
    private BigDecimal montoNeto;
}
