package cl.tenpo.challenge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Suma dos numeros y les aplica el porcentaje adicional que entrega el servicio
 * externo. Ejemplo del enunciado: {@code (5 + 5) + 10% = 11}.
 */
@Service
public class CalculationService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    /** Escala intermedia amplia para no perder precision antes del redondeo final. */
    private static final int INTERMEDIATE_SCALE = 10;
    private static final int RESULT_SCALE = 2;

    private final PercentageProvider percentageProvider;

    public CalculationService(PercentageProvider percentageProvider) {
        this.percentageProvider = percentageProvider;
    }

    public Mono<CalculationResult> calculate(BigDecimal num1, BigDecimal num2) {
        return percentageProvider.getPercentage()
                .map(percentage -> new CalculationResult(num1, num2, percentage, apply(num1.add(num2), percentage)));
    }

    private BigDecimal apply(BigDecimal sum, BigDecimal percentage) {
        BigDecimal increment = sum.multiply(percentage)
                .divide(ONE_HUNDRED, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        return sum.add(increment).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    public record CalculationResult(BigDecimal num1, BigDecimal num2, BigDecimal percentageApplied,
                                    BigDecimal result) {
    }
}
