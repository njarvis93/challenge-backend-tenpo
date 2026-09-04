package cl.tenpo.challenge.infra.percentage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cl.tenpo.challenge.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Mono;

/**
 * Mock del servicio externo de porcentaje, expuesto por la propia aplicacion para
 * que el challenge se pueda levantar sin dependencias de terceros.
 *
 * <p>Devuelve un porcentaje <strong>aleatorio</strong> dentro de un rango configurable:
 * un mock que siempre responde lo mismo no ejercita ni el calculo ni el historial.
 * Con {@code app.mock.seed} el aleatorio se vuelve reproducible, y con
 * {@code app.mock.failure-rate} se provocan fallos para ver los reintentos.
 *
 * <p>Vive fuera de {@code /api} a proposito: no debe consumir cuota del rate limit
 * ni aparecer en el historial de llamadas de la API publica.
 */
@RestController
@RequestMapping("/internal/mock")
@ConditionalOnProperty(name = "app.mock.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Mock interno", description = "Simulacion del servicio externo de porcentaje")
public class MockPercentageController {

    private static final Logger log = LoggerFactory.getLogger(MockPercentageController.class);

    private final AppProperties.Mock config;
    private final Random random;

    public MockPercentageController(AppProperties properties) {
        this.config = properties.mock();
        this.random = config.seed() != null ? new Random(config.seed()) : null;
    }

    @GetMapping("/percentage")
    @Operation(summary = "Devuelve un porcentaje aleatorio, simulando un servicio externo")
    public Mono<PercentageResponse> percentage() {
        if (nextDouble() < config.failureRate()) {
            log.warn("Mock de porcentaje simulando una falla (failure-rate={})", config.failureRate());
            return Mono.error(new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Falla simulada del servicio de porcentaje"));
        }
        return Mono.just(new PercentageResponse(randomPercentage()));
    }

    private BigDecimal randomPercentage() {
        BigDecimal min = config.minPercentage();
        BigDecimal max = config.maxPercentage();
        BigDecimal spread = max.subtract(min);
        return min.add(spread.multiply(BigDecimal.valueOf(nextDouble())))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /** Usa la semilla fija si se configuro; si no, el generador por hilo. */
    private double nextDouble() {
        return random != null ? random.nextDouble() : ThreadLocalRandom.current().nextDouble();
    }

    public record PercentageResponse(BigDecimal percentage) {
    }
}
