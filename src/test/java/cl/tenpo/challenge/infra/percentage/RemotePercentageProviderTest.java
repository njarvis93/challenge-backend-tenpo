package cl.tenpo.challenge.infra.percentage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import cl.tenpo.challenge.config.AppProperties;
import cl.tenpo.challenge.domain.exception.PercentageServiceUnavailableException;
import reactor.test.StepVerifier;

/**
 * Prueba la politica de reintentos contra un servidor HTTP real (WireMock), no
 * contra un doble en memoria: asi se ejercita tambien el WebClient.
 */
class RemotePercentageProviderTest {

    private static final String PATH = "/percentage";
    private static WireMockServer wireMock;

    private RemotePercentageProvider provider;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        var percentageConfig = new AppProperties.Percentage(
                wireMock.baseUrl() + PATH, Duration.ofSeconds(2), 3, Duration.ofMillis(20));
        var properties = new AppProperties(percentageConfig, null, null);
        provider = new RemotePercentageProvider(WebClient.builder().build(), properties);
    }

    @Test
    @DisplayName("Devuelve el porcentaje cuando el servicio responde a la primera")
    void returnsPercentageOnFirstAttempt() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("{\"percentage\": 12.5}")));

        StepVerifier.create(provider.getPercentage())
                .assertNext(percentage -> assertThat(percentage).isEqualByComparingTo(new BigDecimal("12.5")))
                .verifyComplete();

        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("Reintenta y tiene exito en el tercer intento")
    void retriesUntilSuccess() {
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("reintentos")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("segundo"));
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("reintentos")
                .whenScenarioStateIs("segundo")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("tercero"));
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("reintentos")
                .whenScenarioStateIs("tercero")
                .willReturn(okJson("{\"percentage\": 8.0}")));

        StepVerifier.create(provider.getPercentage())
                .assertNext(percentage -> assertThat(percentage).isEqualByComparingTo(new BigDecimal("8.0")))
                .verifyComplete();

        wireMock.verify(3, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("Tras 3 intentos fallidos devuelve el error de servicio no disponible")
    void failsAfterThreeAttempts() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        StepVerifier.create(provider.getPercentage())
                .expectError(PercentageServiceUnavailableException.class)
                .verify();

        // Exactamente 3 llamadas: el original mas 2 reintentos. Ni una mas.
        wireMock.verify(3, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("Un timeout tambien dispara los reintentos")
    void retriesOnTimeout() {
        wireMock.stubFor(get(urlEqualTo(PATH))
                .willReturn(okJson("{\"percentage\": 10.0}").withFixedDelay(3_000)));

        StepVerifier.create(provider.getPercentage())
                .expectError(PercentageServiceUnavailableException.class)
                .verify(Duration.ofSeconds(30));

        wireMock.verify(3, getRequestedFor(urlEqualTo(PATH)));
    }
}
