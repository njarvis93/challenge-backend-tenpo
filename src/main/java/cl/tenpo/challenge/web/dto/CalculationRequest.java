package cl.tenpo.challenge.web.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Numeros a sumar antes de aplicar el porcentaje")
public record CalculationRequest(

        @NotNull(message = "num1 es obligatorio")
        @Schema(example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal num1,

        @NotNull(message = "num2 es obligatorio")
        @Schema(example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal num2) {
}
