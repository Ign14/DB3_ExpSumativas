package cl.duoc.bancoxyz.bff.common.error;

import cl.duoc.bancoxyz.bff.common.dto.ErrorResponse;
import cl.duoc.bancoxyz.bff.common.exception.CuentaNoEncontradaException;
import cl.duoc.bancoxyz.bff.common.exception.ParametroInvalidoException;
import cl.duoc.bancoxyz.bff.common.exception.ServicioCoreNoDisponibleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Manejo de errores compartido por los tres BFF. Cada aplicación lo incorpora
 * agregando el paquete {@code cl.duoc.bancoxyz.bff.common} a su
 * {@code scanBasePackages}.
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

    /** Un parámetro con tipo incorrecto (por ejemplo ?limite=abc) también es un 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> tipoInvalido(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "El parámetro '" + ex.getName() + "' tiene un valor inválido: " + ex.getValue()));
    }

    @ExceptionHandler(ServicioCoreNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> servicioNoDisponible(ServicioCoreNoDisponibleException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(ex.getMessage()));
    }
}
