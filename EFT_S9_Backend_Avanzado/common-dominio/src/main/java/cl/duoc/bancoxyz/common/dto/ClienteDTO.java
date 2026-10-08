package cl.duoc.bancoxyz.common.dto;

import java.math.BigDecimal;

/**
 * Perfil del cliente, tal como lo expone clientes-service.
 *
 * Reune la informacion personal del titular con dos datos que el servicio
 * mantiene al dia escuchando los eventos de Kafka: cuantas operaciones ha
 * realizado y cuantas alertas de seguridad acumula. Ninguno de los dos se le
 * pregunta a otro servicio en el momento de la consulta, que es la ventaja de
 * mantener una vista propia alimentada por eventos: la consulta del perfil no
 * depende de que cuentas-service o pagos-service esten arriba.
 */
public record ClienteDTO(
        Long clienteId,
        String nombre,
        String segmento,
        Integer edad,
        String tipoCuentaPrincipal,
        BigDecimal saldoReferencial,
        int operacionesRegistradas,
        int alertasRegistradas,
        String ultimaOperacion
) {
}
