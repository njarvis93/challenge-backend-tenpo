package cl.tenpo.challenge.domain.exception;

/**
 * El servicio externo de porcentaje no respondio tras agotar todos los intentos.
 * Se traduce a un HTTP 503 en {@code GlobalExceptionHandler}.
 */
public class PercentageServiceUnavailableException extends RuntimeException {

    public PercentageServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
