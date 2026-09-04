package cl.tenpo.challenge.infra.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifica el esquema de Flyway y la paginacion sobre PostgreSQL real.
 */
@DataJpaTest
@Testcontainers
// Se usa el PostgreSQL del contenedor, no una base embebida: el esquema lo crea Flyway.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class CallHistoryRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private CallHistoryRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Persiste todos los campos del registro")
    void savesEveryField() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CallHistory saved = repository.save(new CallHistory(now, "/api/v1/calculations", "POST",
                "body={\"num1\":5,\"num2\":5}", "{\"result\":11.00}", null, 200, 42L));

        assertThat(saved.getId()).isNotNull();
        CallHistory found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getEndpoint()).isEqualTo("/api/v1/calculations");
        assertThat(found.getHttpMethod()).isEqualTo("POST");
        assertThat(found.getParameters()).contains("num1");
        assertThat(found.getResponseBody()).contains("11.00");
        assertThat(found.getErrorMessage()).isNull();
        assertThat(found.getStatusCode()).isEqualTo(200);
        assertThat(found.getDurationMs()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Guarda tambien las llamadas con error")
    void savesFailedCalls() {
        CallHistory saved = repository.save(new CallHistory(Instant.now(), "/api/v1/calculations", "POST",
                "body={}", null, "Demasiadas peticiones", 429, 3L));

        CallHistory found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatusCode()).isEqualTo(429);
        assertThat(found.getErrorMessage()).isEqualTo("Demasiadas peticiones");
        assertThat(found.getResponseBody()).isNull();
    }

    @Test
    @DisplayName("Pagina ordenando por fecha descendente")
    void paginatesNewestFirst() {
        Instant base = Instant.now().minusSeconds(100);
        for (int i = 0; i < 7; i++) {
            repository.save(new CallHistory(base.plusSeconds(i), "/api/v1/history", "GET",
                    "query=page=" + i, "{}", null, 200, 1L));
        }

        var sortByDateDesc = Sort.by(Sort.Direction.DESC, "calledAt");
        var firstPage = repository.findAll(PageRequest.of(0, 3, sortByDateDesc));
        var lastPage = repository.findAll(PageRequest.of(2, 3, sortByDateDesc));

        assertThat(firstPage.getTotalElements()).isEqualTo(7);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(3);
        assertThat(firstPage.getContent().getFirst().getParameters()).isEqualTo("query=page=6");
        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.getContent().getFirst().getParameters()).isEqualTo("query=page=0");
    }
}
