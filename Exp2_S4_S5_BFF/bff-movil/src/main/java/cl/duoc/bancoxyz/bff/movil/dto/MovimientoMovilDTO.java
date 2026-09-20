package cl.duoc.bancoxyz.bff.movil.dto;

import java.math.BigDecimal;

/**
 * Movimiento reducido a lo que muestra el listado de la app. Se omite la
 * descripción, que es texto libre y el campo que más pesa.
 */
public record MovimientoMovilDTO(String fecha, String tipo, BigDecimal monto) {
}
