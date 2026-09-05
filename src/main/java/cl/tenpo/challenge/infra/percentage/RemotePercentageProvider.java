package cl.tenpo.challenge.infra.percentage;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import cl.tenpo.challenge.config.AppProperties;
import cl.tenpo.challenge.domain.PercentageProvider;
import cl.tenpo.challenge.domain.exception.PercentageServiceUnavailableException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Cliente HTTP del servicio externo de porcentaje.
 *
 * <p>Reintenta con backoff exponencial hasta completar
 * {@code app.percentage.max-attempts} intentos <em>totales</em> (por defecto 3);
 * si todos fallan, el error se traduce a una excepcion de dominio que el
 * manejador global convierte en un 503 con mensaje descriptivo.
 */
@Component
public class RemotePercentageProvider implements PercentageProvider {

    private static final Logger log = LoggerFactory.getLogger(RemotePercentageProvider.class);

    private final WebClient webClient;
    private final AppProperties.Percentage config;

    public RemotePercentageProvider(WebClient percentageWebClient, AppProperties properties) {
        this.webClient = percentageWebClient;
        this.config = properties.percentage();
    }

    @Override
    public Mono<BigDecimal> getPercentage() {
        // maxAttempts cuenta el intento original, por eso se reintenta una vez menos.
        long maxRetries = Math.max(0, config.maxAttempts() - 1L);

        // El contador vive en el defer, fuera de la cadena que reintenta, para que
        // sobreviva a las resuscripciones y diga en que intento se obtuvo la respuesta.
        return Mono.defer(() -> {
            AtomicInteger attempt = new AtomicInteger();

            return webClient.get()
                    .uri(config.url())
                    .retrieve()
                    .bodyToMono(PercentagePayload.class)
                    .timeout(config.timeout())
                    .map(PercentagePayload::percentage)
                    .doOnSubscribe(subscription -> attempt.incrementAndGet())
                    .doOnNext(percentage -> log.info(
                            "Porcentaje obtenido del servicio externo: {}% (intento {} de {})",
                            percentage, attempt.get(), config.maxAttempts()))
                    .retryWhen(Retry.backoff(maxRetries, config.initialBackoff())
                            .doBeforeRetry(signal -> log.warn(
                                    "Fallo la llamada al servicio de porcentaje (intento {} de {}): {}",
                                    signal.totalRetries() + 1, config.maxAttempts(),
                                    signal.failure().toString())));
        }).onErrorMap(error -> new PercentageServiceUnavailableException(
                "El servicio de porcentaje no respondio tras %d intentos".formatted(config.maxAttempts()),
                error));
    }

    /** Respuesta del servicio externo: {@code {"percentage": 12.4}}. */
    record PercentagePayload(BigDecimal percentage) {
    }
}
