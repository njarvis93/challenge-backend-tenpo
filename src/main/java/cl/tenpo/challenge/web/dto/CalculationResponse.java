package cl.tenpo.challenge.web.dto;

import java.math.BigDecimal;

import cl.tenpo.challenge.domain.CalculationService.CalculationResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resultado del calculo con el porcentaje aplicado")
public record CalculationResponse(

        @Schema(example = "5") BigDecimal num1,
        @Schema(example = "5") BigDecimal num2,

        // Se expone el porcentaje usado porque es aleatorio: sin este dato el
        // resultado no seria verificable por el cliente.
        @Schema(description = "Porcentaje entregado por el servicio externo", example = "12.4")
        BigDecimal percentageApplied,

        @Schema(description = "(num1 + num2) incrementado en percentageApplied", example = "11.24")
        BigDecimal result) {

    public static CalculationResponse from(CalculationResult result) {
        return new CalculationResponse(result.num1(), result.num2(), result.percentageApplied(), result.result());
    }
}
