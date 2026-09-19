package cl.duoc.bancoxyz.bff.movil.dto;

import java.math.BigDecimal;

/**
 * Version minima de un movimiento: solo fecha, tipo y monto. Se omite a
 * proposito la descripcion (texto libre, es el campo que mas pesa) porque
 * la app movil no la muestra en el listado resumido.
 */
public record MovimientoMovilDTO(String fecha, String tipo, BigDecimal monto) {
}
