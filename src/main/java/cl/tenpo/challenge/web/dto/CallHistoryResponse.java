package cl.tenpo.challenge.web.dto;

import java.time.Instant;

import cl.tenpo.challenge.infra.history.CallHistory;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Registro de una llamada recibida por la API")
public record CallHistoryResponse(

        Long id,
        @Schema(description = "Fecha y hora de la llamada (UTC)") Instant calledAt,
        @Schema(example = "/api/v1/calculations") String endpoint,
        @Schema(example = "POST") String httpMethod,
        @Schema(description = "Query params y cuerpo recibido") String parameters,
        @Schema(description = "Respuesta devuelta, si la llamada fue exitosa") String response,
        @Schema(description = "Mensaje de error, si la llamada fallo") String error,
        @Schema(example = "200") Integer statusCode,
        @Schema(description = "Duracion en milisegundos") Long durationMs) {

    public static CallHistoryResponse from(CallHistory entity) {
        return new CallHistoryResponse(entity.getId(), entity.getCalledAt(), entity.getEndpoint(),
                entity.getHttpMethod(), entity.getParameters(), entity.getResponseBody(),
                entity.getErrorMessage(), entity.getStatusCode(), entity.getDurationMs());
    }
}
