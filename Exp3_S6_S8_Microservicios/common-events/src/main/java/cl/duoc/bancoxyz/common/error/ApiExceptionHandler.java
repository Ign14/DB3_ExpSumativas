package cl.duoc.bancoxyz.common.error;

import cl.duoc.bancoxyz.common.dto.ErrorResponse;
import cl.duoc.bancoxyz.common.excepcion.ParametroInvalidoException;
import cl.duoc.bancoxyz.common.excepcion.RecursoNoEncontradoException;
import cl.duoc.bancoxyz.common.excepcion.ServicioNoDisponibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Traduce las excepciones a respuestas HTTP con el mismo cuerpo en todos los
 * microservicios. Esta en el modulo compartido para que las dos APIs respondan
 * igual ante el mismo tipo de error, sin repetir el mapeo en cada una.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> noEncontrado(RecursoNoEncontradoException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ParametroInvalidoException.class)
    public ResponseEntity<ErrorResponse> parametroInvalido(ParametroInvalidoException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> tipoInvalido(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("El parametro '" + ex.getName() + "' tiene un valor invalido."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> cuerpoInvalido(MethodArgumentNotValidException ex) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("cuerpo de la peticion invalido");
        return ResponseEntity.badRequest().body(new ErrorResponse(detalle));
    }

    @ExceptionHandler(ServicioNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> dependenciaCaida(ServicioNoDisponibleException ex) {
        log.warn("Dependencia no disponible: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Ultimo recurso: se registra la traza completa en el log del servicio y al
     * cliente se le devuelve un mensaje generico, para no filtrar detalles
     * internos en la respuesta.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> errorInesperado(Exception ex) {
        log.error("Error no controlado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Error interno del servicio."));
    }
}
