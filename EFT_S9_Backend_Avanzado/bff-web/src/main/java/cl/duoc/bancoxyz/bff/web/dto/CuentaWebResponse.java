package cl.duoc.bancoxyz.bff.web.dto;

import cl.duoc.bancoxyz.common.dto.ClienteDTO;
import cl.duoc.bancoxyz.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.common.dto.ResumenMovimientosDTO;

import java.util.List;

/**
 * Payload del canal web: datos de la cuenta, perfil del titular, historial
 * entero y totales, en una sola respuesta pensada para una interfaz de
 * escritorio.
 *
 * {@code perfil} puede venir nulo, y eso no es un descuido del contrato. Es la
 * misma idea que la respuesta degradada de pagos-service, aplicada en el canal:
 * si clientes-service no responde, el operador igual necesita ver el saldo y el
 * historial. El campo nulo le dice a la interfaz que ese bloque no esta
 * disponible, en vez de dejar la pantalla entera en blanco por un dato
 * secundario.
 */
public record CuentaWebResponse(
        CuentaDTO cuenta,
        ClienteDTO perfil,
        List<MovimientoDTO> historialCompleto,
        ResumenMovimientosDTO resumen
) {
}
