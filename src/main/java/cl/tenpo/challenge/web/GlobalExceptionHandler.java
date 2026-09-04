package cl.tenpo.challenge.web;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import cl.tenpo.challenge.domain.exception.PercentageServiceUnavailableException;
import jakarta.validation.ConstraintViolationException;

/**
 * Traduce las excepciones a respuestas RFC 7807 ({@code application/problem+json})
 * con mensajes descriptivos, tanto para la serie 4XX como para la 5XX.
 *
 * <p>Nota: el 429 del rate limit se emite directamente en
 * {@link cl.tenpo.challenge.web.filter.RateLimitWebFilter}, porque los errores
 * lanzados desde un {@code WebFilter} no pasan por este advice.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Cuerpo JSON invalido o campos que no cumplen las validaciones. */
    @ExceptionHandler(ServerWebInputException.class)
    ProblemDetail handleInvalidInput(ServerWebInputException exception, ServerWebExchange exchange) {
        String detail;
        if (exception instanceof org.springframework.web.bind.support.WebExchangeBindException bindException) {
            detail = bindException.getFieldErrors().stream()
                    .map(error -> "%s: %s".formatted(error.getField(), error.getDefaultMessage()))
                    .collect(Collectors.joining("; "));
        } else {
            // El motivo original ("Failed to read HTTP message") no le dice nada al cliente.
            detail = "El cuerpo de la peticion no es un JSON valido o no tiene el formato esperado. "
                    + "Se esperan los campos numericos num1 y num2.";
        }
        return problem(HttpStatus.BAD_REQUEST, "Peticion invalida", detail, exchange);
    }

    /** Restricciones sobre parametros de query, por ejemplo un size fuera de rango. */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception, ServerWebExchange exchange) {
        String detail = exception.getConstraintViolations().stream()
                .map(violation -> "%s %s".formatted(violation.getPropertyPath(), violation.getMessage()))
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Parametros invalidos", detail, exchange);
    }

    /** El servicio externo de porcentaje agoto los reintentos. */
    @ExceptionHandler(PercentageServiceUnavailableException.class)
    ProblemDetail handlePercentageUnavailable(PercentageServiceUnavailableException exception,
                                              ServerWebExchange exchange) {
        log.error("Servicio de porcentaje no disponible: {}", exception.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Servicio de porcentaje no disponible",
                exception.getMessage() + ". Intentalo nuevamente en unos segundos.", exchange);
    }

    /** Errores con status explicito (404 de rutas inexistentes, entre otros). */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException exception, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        if (status == HttpStatus.NOT_FOUND) {
            // El motivo por defecto habla de "static resource", que confunde en una API REST.
            return problem(status, "Recurso no encontrado",
                    "No existe el endpoint %s. Consulta /swagger-ui.html para ver los disponibles."
                            .formatted(exchange.getRequest().getPath().value()),
                    exchange);
        }
        String detail = exception.getReason() != null ? exception.getReason() : status.getReasonPhrase();
        return problem(status, status.getReasonPhrase(), detail, exchange);
    }

    /** Red de seguridad: nada debe escapar sin un cuerpo descriptivo. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, ServerWebExchange exchange) {
        log.error("Error inesperado procesando {}", exchange.getRequest().getPath(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "Ocurrio un error inesperado al procesar la peticion.", exchange);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, ServerWebExchange exchange) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create("https://tenpo.cl/errors/" + status.value()));
        problemDetail.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}
