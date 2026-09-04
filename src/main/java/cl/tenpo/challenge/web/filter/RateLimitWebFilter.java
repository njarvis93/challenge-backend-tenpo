package cl.tenpo.challenge.web.filter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import cl.tenpo.challenge.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Limita el trafico de {@code /api/**} a N requests por minuto (3 por defecto).
 *
 * <p>Usa un token bucket alojado en Redis, de modo que el limite es global al
 * servicio y no por instancia. Todas las operaciones contra Redis son asincronas
 * ({@code CompletableFuture} envuelto en {@code Mono}), asi que el event loop no
 * se bloquea.
 *
 * <p>Se ejecuta despues del filtro de historial para que las llamadas rechazadas
 * con 429 tambien queden registradas.
 */
@Component
@Order(RateLimitWebFilter.ORDER)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitWebFilter implements WebFilter {

    public static final int ORDER = 10;
    private static final String RETRY_AFTER_HEADER = "X-Rate-Limit-Retry-After-Seconds";

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);

    private final AsyncProxyManager<byte[]> proxyManager;
    private final ObjectMapper objectMapper;
    private final AppProperties.RateLimit config;
    private final BucketConfiguration bucketConfiguration;
    private final byte[] bucketKey;

    public RateLimitWebFilter(AsyncProxyManager<byte[]> proxyManager, ObjectMapper objectMapper,
                              AppProperties properties) {
        this.proxyManager = proxyManager;
        this.objectMapper = objectMapper;
        this.config = properties.rateLimit();
        this.bucketKey = config.key().getBytes(StandardCharsets.UTF_8);
        this.bucketConfiguration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(config.capacity())
                        .refillGreedy(config.capacity(), config.period())
                        .build())
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }

        AsyncBucketProxy bucket = proxyManager.builder().build(bucketKey, () -> completed(bucketConfiguration));

        return Mono.fromFuture(bucket.tryConsumeAndReturnRemaining(1))
                .flatMap(probe -> probe.isConsumed()
                        ? proceed(exchange, chain, probe)
                        : rejectWithTooManyRequests(exchange, probe));
    }

    private Mono<Void> proceed(ServerWebExchange exchange, WebFilterChain chain, ConsumptionProbe probe) {
        exchange.getResponse().getHeaders().add("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
        return chain.filter(exchange);
    }

    private Mono<Void> rejectWithTooManyRequests(ServerWebExchange exchange, ConsumptionProbe probe) {
        long retryAfterSeconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        String path = exchange.getRequest().getPath().value();
        log.warn("Rate limit excedido en {}: reintentar en {}s", path, retryAfterSeconds);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Se supero el limite de %d peticiones por %s. Reintenta en %d segundos."
                        .formatted(config.capacity(), humanize(config.period()), retryAfterSeconds));
        problem.setTitle("Demasiadas peticiones");
        problem.setType(URI.create("https://tenpo.cl/errors/429"));
        problem.setInstance(URI.create(path));
        problem.setProperty("timestamp", Instant.now());

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().add(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));

        DataBuffer body;
        try {
            body = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(problem));
        } catch (JacksonException e) {
            // Practicamente imposible; se degrada a un cuerpo minimo antes que fallar.
            body = response.bufferFactory().wrap("{\"status\":429}".getBytes(StandardCharsets.UTF_8));
        }
        return response.writeWith(Mono.just(body));
    }

    private static String humanize(Duration period) {
        return period.toMinutes() > 0 ? period.toMinutes() + " minuto(s)" : period.toSeconds() + " segundo(s)";
    }

    private static java.util.concurrent.CompletableFuture<BucketConfiguration> completed(BucketConfiguration cfg) {
        return java.util.concurrent.CompletableFuture.completedFuture(cfg);
    }
}
