package cl.duoc.bancoxyz.bff.common.error;

import cl.duoc.bancoxyz.bff.common.dto.ErrorResponse;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ParametroInvalidoException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Manejo de errores compartido por los tres BFF: una cuenta inexistente
 * responde 404, un parametro invalido 400, y un servicio core caido o con
 * error responde 502 (el fallo es del backend, no del cliente del BFF).
 *
 * <p>Vive en common-model y no duplicado en cada canal; cada aplicacion BFF
 * lo incorpora incluyendo el paquete {@code cl.duoc.bancoxyz.bff.common} en
 * su {@code scanBasePackages}.</p>
 */
@RestControllerAdvice
public class BffExceptionHandler {

    @ExceptionHandler(CuentaNoEncontradaException.class)
    public ResponseEntity<ErrorResponse> cuentaNoEncontrada(CuentaNoEncontradaException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ParametroInvalidoException.class)
    public ResponseEntity<ErrorResponse> parametroInvalido(ParametroInvalidoException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ServicioCoreNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> servicioNoDisponible(ServicioCoreNoDisponibleException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(ex.getMessage()));
    }
}
