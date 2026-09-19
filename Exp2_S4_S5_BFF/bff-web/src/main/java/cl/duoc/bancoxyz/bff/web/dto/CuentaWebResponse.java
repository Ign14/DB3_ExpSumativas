package cl.duoc.bancoxyz.bff.web.dto;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;

import java.util.List;

/**
 * Payload "completo" que arma el BFF Web: agrega, en una sola respuesta,
 * datos de dos servicios core distintos (cuenta + movimientos) y expone el
 * historial entero, pensado para una interfaz de escritorio con espacio
 * para mostrar todo.
 */
public record CuentaWebResponse(
        CuentaDTO cuenta,
        List<MovimientoDTO> historialCompleto,
        ResumenMovimientosDTO resumen
) {
}
