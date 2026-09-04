package cl.tenpo.challenge.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.tenpo.challenge.domain.CallHistoryService;
import cl.tenpo.challenge.web.dto.CallHistoryResponse;
import cl.tenpo.challenge.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import reactor.core.publisher.Mono;

@RestController
@Validated // necesario para que se apliquen las restricciones de los @RequestParam
@RequestMapping(value = "/api/v1/history", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Historial", description = "Consulta paginada de las llamadas recibidas por la API")
public class HistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CallHistoryService callHistoryService;

    public HistoryController(CallHistoryService callHistoryService) {
        this.callHistoryService = callHistoryService;
    }

    @GetMapping
    @Operation(summary = "Lista el historial de llamadas, mas recientes primero")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina de historial"),
            @ApiResponse(responseCode = "400", description = "Parametros de paginacion invalidos"),
            @ApiResponse(responseCode = "429", description = "Se supero el limite de 3 requests por minuto")
    })
    public Mono<PageResponse<CallHistoryResponse>> history(

            @Parameter(description = "Numero de pagina, base 0")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Tamano de pagina (maximo 100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "calledAt"));

        return callHistoryService.findAll(pageRequest)
                .map(result -> PageResponse.from(result, CallHistoryResponse::from));
    }
}
