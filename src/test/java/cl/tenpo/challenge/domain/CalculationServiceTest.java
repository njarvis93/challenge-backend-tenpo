package cl.tenpo.challenge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import cl.tenpo.challenge.domain.exception.PercentageServiceUnavailableException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CalculationServiceTest {

    private static CalculationService serviceReturning(String percentage) {
        PercentageProvider provider = () -> Mono.just(new BigDecimal(percentage));
        return new CalculationService(provider);
    }

    @Test
    @DisplayName("El ejemplo del enunciado: (5 + 5) + 10% = 11")
    void appliesPercentageFromTheStatement() {
        StepVerifier.create(serviceReturning("10").calculate(new BigDecimal("5"), new BigDecimal("5")))
                .assertNext(result -> {
                    assertThat(result.result()).isEqualByComparingTo("11.00");
                    assertThat(result.percentageApplied()).isEqualByComparingTo("10");
                })
                .verifyComplete();
    }

    @ParameterizedTest(name = "({0} + {1}) + {2}% = {3}")
    @CsvSource({
            "5, 5, 10, 11.00",
            "0, 0, 15, 0.00",
            "10, 5, 20, 18.00",
            "-5, 10, 10, 5.50",
            "3.33, 3.33, 7.5, 7.16",   // redondeo HALF_UP a 2 decimales
            "1000000, 1, 5.5, 1055001.06"   // 1055001.055 redondea hacia arriba
    })
    void calculatesWithVariousInputs(String num1, String num2, String percentage, String expected) {
        StepVerifier.create(serviceReturning(percentage).calculate(new BigDecimal(num1), new BigDecimal(num2)))
                .assertNext(result -> assertThat(result.result()).isEqualByComparingTo(expected))
                .verifyComplete();
    }

    @Test
    @DisplayName("El resultado siempre trae 2 decimales")
    void resultAlwaysHasTwoDecimals() {
        StepVerifier.create(serviceReturning("10").calculate(new BigDecimal("1"), new BigDecimal("1")))
                .assertNext(result -> assertThat(result.result().scale()).isEqualTo(2))
                .verifyComplete();
    }

    @Test
    @DisplayName("Si el proveedor falla, el error se propaga sin calcular nada")
    void propagatesProviderFailure() {
        PercentageProvider failing = () -> Mono.error(
                new PercentageServiceUnavailableException("sin respuesta", new RuntimeException()));

        StepVerifier.create(new CalculationService(failing).calculate(BigDecimal.ONE, BigDecimal.ONE))
                .expectError(PercentageServiceUnavailableException.class)
                .verify();
    }
}
