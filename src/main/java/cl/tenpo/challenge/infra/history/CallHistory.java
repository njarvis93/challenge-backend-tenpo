package cl.tenpo.challenge.infra.history;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una fila por llamada recibida en {@code /api/**}. El esquema lo gestiona Flyway
 * ({@code V1__create_call_history.sql}); Hibernate solo lo valida.
 */
@Entity
@Table(name = "call_history")
public class CallHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;

    @Column(nullable = false, length = 512)
    private String endpoint;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(columnDefinition = "text")
    private String parameters;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected CallHistory() {
        // requerido por JPA
    }

    public CallHistory(Instant calledAt, String endpoint, String httpMethod, String parameters,
                       String responseBody, String errorMessage, Integer statusCode, Long durationMs) {
        this.calledAt = calledAt;
        this.endpoint = endpoint;
        this.httpMethod = httpMethod;
        this.parameters = parameters;
        this.responseBody = responseBody;
        this.errorMessage = errorMessage;
        this.statusCode = statusCode;
        this.durationMs = durationMs;
    }

    public Long getId() {
        return id;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getParameters() {
        return parameters;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Long getDurationMs() {
        return durationMs;
    }
}
