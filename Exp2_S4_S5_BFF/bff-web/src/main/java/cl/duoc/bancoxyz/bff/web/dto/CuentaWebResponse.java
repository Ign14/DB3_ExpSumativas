package cl.duoc.bancoxyz.bff.web.dto;

import cl.duoc.bancoxyz.bff.common.dto.CuentaDTO;
import cl.duoc.bancoxyz.bff.common.dto.MovimientoDTO;
import cl.duoc.bancoxyz.bff.common.dto.ResumenMovimientosDTO;

import java.util.List;

/**
 * Payload del canal web: datos de la cuenta, historial entero y totales, en
 * una sola respuesta pensada para una interfaz de escritorio.
 */
public record CuentaWebResponse(
        CuentaDTO cuenta,
        List<MovimientoDTO> historialCompleto,
        ResumenMovimientosDTO resumen
) {
}
