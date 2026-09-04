package cl.tenpo.challenge.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.tenpo.challenge.domain.CalculationService;
import cl.tenpo.challenge.web.dto.CalculationRequest;
import cl.tenpo.challenge.web.dto.CalculationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(value = "/api/v1/calculations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Calculo", description = "Suma dos numeros aplicando un porcentaje externo")
public class CalculationController {

    private final CalculationService calculationService;

    public CalculationController(CalculationService calculationService) {
        this.calculationService = calculationService;
    }

    @PostMapping
    @Operation(summary = "Calcula (num1 + num2) incrementado en el porcentaje del servicio externo",
            description = "El porcentaje se consulta en cada llamada. Si el servicio externo falla, "
                    + "se reintenta hasta 3 veces antes de devolver 503.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Calculo realizado"),
            @ApiResponse(responseCode = "400", description = "num1 o num2 ausentes o invalidos"),
            @ApiResponse(responseCode = "429", description = "Se supero el limite de 3 requests por minuto"),
            @ApiResponse(responseCode = "503", description = "El servicio de porcentaje no respondio tras 3 intentos")
    })
    public Mono<CalculationResponse> calculate(@Valid @RequestBody CalculationRequest request) {
        return calculationService.calculate(request.num1(), request.num2())
                .map(CalculationResponse::from);
    }
}
