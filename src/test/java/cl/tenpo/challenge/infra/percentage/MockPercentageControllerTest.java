package cl.tenpo.challenge.infra.percentage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cl.tenpo.challenge.config.AppProperties;
import reactor.test.StepVerifier;

/**
 * Comprueba los dos modos de fallo del mock: el determinista ({@code fail-first})
 * y el probabilistico ({@code failure-rate}).
 */
class MockPercentageControllerTest {

    private static MockPercentageController controller(double failureRate, int failFirst) {
        var mock = new AppProperties.Mock(true, new BigDecimal("5.0"), new BigDecimal("20.0"),
                failureRate, failFirst, null);
        return new MockPercentageController(new AppProperties(null, mock, null));
    }

    @Test
    @DisplayName("fail-first hace fallar las N primeras llamadas y responder bien a la siguiente")
    void failsTheFirstCallsAndThenSucceeds() {
        var controller = controller(0.0, 2);

        for (int call = 1; call <= 2; call++) {
            StepVerifier.create(controller.percentage())
                    .expectErrorSatisfies(error -> assertThat(error)
                            .isInstanceOf(ResponseStatusException.class)
                            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR))
                    .verify();
        }

        // La tercera llamada, la que el cliente ve como segundo reintento, ya responde.
        StepVerifier.create(controller.percentage())
                .assertNext(response -> assertThat(response.percentage())
                        .isBetween(new BigDecimal("5.0"), new BigDecimal("20.0")))
                .verifyComplete();
    }

    @Test
    @DisplayName("Sin fail-first ni failure-rate el mock siempre responde")
    void alwaysRespondsWhenNoFailureIsConfigured() {
        var controller = controller(0.0, 0);

        StepVerifier.create(controller.percentage())
                .assertNext(response -> assertThat(response.percentage()).isNotNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("failure-rate a 1.0 hace fallar todas las llamadas")
    void alwaysFailsWhenFailureRateIsOne() {
        var controller = controller(1.0, 0);

        StepVerifier.create(controller.percentage())
                .expectError(ResponseStatusException.class)
                .verify();
    }
}
