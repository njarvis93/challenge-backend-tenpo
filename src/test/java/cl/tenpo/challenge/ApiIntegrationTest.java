package cl.tenpo.challenge;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import cl.tenpo.challenge.infra.history.CallHistoryRepository;
import io.lettuce.core.RedisClient;

/**
 * Recorrido end-to-end sobre la API con PostgreSQL y Redis reales (Testcontainers)
 * y el servicio externo de porcentaje simulado con WireMock, para que los
 * resultados sean deterministas pese a que el mock de la app sea aleatorio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class ApiIntegrationTest {

    private static final String PERCENTAGE_PATH = "/percentage";
    private static WireMockServer wireMock;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CallHistoryRepository callHistoryRepository;

    @Autowired
    private RedisClient redisClient;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.rate-limit.redis-url",
                () -> "redis://%s:%d".formatted(redis.getHost(), redis.getFirstMappedPort()));
        registry.add("app.percentage.url", () -> wireMock.baseUrl() + PERCENTAGE_PATH);
        registry.add("app.percentage.initial-backoff", () -> "20ms");
    }

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo(PERCENTAGE_PATH)).willReturn(okJson("{\"percentage\": 10.0}")));
        callHistoryRepository.deleteAll();
        // Cada test parte con la cuota de rate limit intacta.
        try (var connection = redisClient.connect()) {
            connection.sync().flushall();
        }
    }

    @Test
    @DisplayName("POST /api/v1/calculations aplica el porcentaje del servicio externo")
    void calculatesApplyingPercentage() {
        webTestClient.post().uri("/api/v1/calculations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"num1\": 5, \"num2\": 5}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result").value(value ->
                        assertThat(new java.math.BigDecimal(value.toString())).isEqualByComparingTo("11.00"))
                .jsonPath("$.percentageApplied").value(value ->
                        assertThat(new java.math.BigDecimal(value.toString())).isEqualByComparingTo("10.0"));
    }

    @Test
    @DisplayName("Un cuerpo sin num2 devuelve 400 con detalle del campo")
    void rejectsInvalidBody() {
        webTestClient.post().uri("/api/v1/calculations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"num1\": 5}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("num2"));
    }

    @Test
    @DisplayName("Si el servicio externo falla siempre, responde 503 tras 3 intentos")
    void returnsServiceUnavailableWhenPercentageServiceIsDown() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo(PERCENTAGE_PATH)).willReturn(aResponse().withStatus(500)));

        webTestClient.post().uri("/api/v1/calculations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"num1\": 1, \"num2\": 1}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Servicio de porcentaje no disponible");

        wireMock.verify(3, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlEqualTo(PERCENTAGE_PATH)));
    }

    @Test
    @DisplayName("La cuarta llamada dentro del minuto recibe 429")
    void enforcesThreeRequestsPerMinute() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            webTestClient.get().uri("/api/v1/history")
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.get().uri("/api/v1/history")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("X-Rate-Limit-Retry-After-Seconds")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Demasiadas peticiones")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("3"));
    }

    @Test
    @DisplayName("El historial registra la llamada de forma asincrona y la devuelve paginada")
    void recordsCallsInHistory() {
        webTestClient.post().uri("/api/v1/calculations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"num1\": 2, \"num2\": 3}")
                .exchange()
                .expectStatus().isOk();

        // El registro es asincrono: se espera a que aparezca en vez de asumirlo.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var entries = callHistoryRepository.findAll();
            assertThat(entries).hasSize(1);
            var entry = entries.getFirst();
            assertThat(entry.getEndpoint()).isEqualTo("/api/v1/calculations");
            assertThat(entry.getHttpMethod()).isEqualTo("POST");
            assertThat(entry.getParameters()).contains("num1");
            assertThat(entry.getResponseBody()).contains("result");
            assertThat(entry.getStatusCode()).isEqualTo(200);
            assertThat(entry.getDurationMs()).isNotNull();
        });

        webTestClient.get().uri("/api/v1/history?page=0&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(10)
                .jsonPath("$.content[0].endpoint").isEqualTo("/api/v1/calculations");
    }

    @Test
    @DisplayName("Tambien se registran las llamadas rechazadas por rate limit")
    void recordsRejectedCalls() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            webTestClient.get().uri("/api/v1/history").exchange();
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(callHistoryRepository.findAll())
                        .anyMatch(entry -> entry.getStatusCode() != null && entry.getStatusCode() == 429));
    }

    @Test
    @DisplayName("La respuesta del propio historial no se guarda dentro del historial")
    void doesNotStoreItsOwnResponseBody() {
        webTestClient.get().uri("/api/v1/history").exchange().expectStatus().isOk();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var entries = callHistoryRepository.findAll();
            assertThat(entries).hasSize(1);
            // Sin esto, cada consulta guardaria el historial completo anterior anidado.
            assertThat(entries.getFirst().getResponseBody()).doesNotContain("content");
        });
    }

    @Test
    @DisplayName("Una ruta inexistente devuelve 404")
    void returnsNotFoundForUnknownRoutes() {
        webTestClient.get().uri("/api/v1/no-existe")
                .exchange()
                .expectStatus().isNotFound();
    }
}
