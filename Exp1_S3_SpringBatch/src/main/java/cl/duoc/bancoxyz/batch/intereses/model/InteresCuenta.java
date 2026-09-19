package cl.duoc.bancoxyz.batch.intereses.model;

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
 * Resultado del cálculo mensual de intereses para una cuenta
 * (tabla {@code interes_cuenta}).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InteresCuenta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cuentaId;
    private String nombre;
    private String tipoCuenta;
    private BigDecimal saldoInicial;
    private BigDecimal tasaAplicada;
    private BigDecimal interesCalculado;
    private BigDecimal saldoFinal;
    private LocalDateTime fechaProceso;
}
