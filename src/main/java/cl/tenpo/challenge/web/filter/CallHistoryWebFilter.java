package cl.tenpo.challenge.web.filter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.Publisher;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import cl.tenpo.challenge.domain.CallHistoryService;
import cl.tenpo.challenge.infra.history.CallHistory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Registra toda llamada a {@code /api/**} en el historial: fecha, endpoint,
 * parametros recibidos, respuesta o error, status y duracion.
 *
 * <p>Es el filtro <strong>mas externo</strong> (orden 0), por delante del rate
 * limit, para que tambien queden registradas las peticiones rechazadas con 429.
 *
 * <p>La escritura en base de datos es asincrona y se dispara fuera de la cadena
 * de respuesta ({@link CallHistoryService#recordAsync}), de modo que no suma
 * latencia al request ni puede romperlo si la base de datos falla.
 */
@Component
@Order(CallHistoryWebFilter.ORDER)
public class CallHistoryWebFilter implements WebFilter {

    public static final int ORDER = 0;
    /** Los cuerpos se truncan: el historial no es un almacen de payloads completos. */
    private static final int MAX_CAPTURED_CHARS = 2_000;

    /**
     * El endpoint de historial es auto-referencial: guardar su respuesta significaria
     * guardar dentro de cada registro el historial completo anterior, anidado y
     * escapado una vez mas en cada llamada. Se registra la llamada, pero no su cuerpo.
     */
    private static final String HISTORY_PATH = "/api/v1/history";
    private static final String OMITTED_BODY = "[omitido: la respuesta es el propio historial]";

    private final CallHistoryService callHistoryService;

    public CallHistoryWebFilter(CallHistoryService callHistoryService) {
        this.callHistoryService = callHistoryService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }

        Instant calledAt = Instant.now();
        long startNanos = System.nanoTime();

        // El cuerpo del request solo se puede leer una vez, asi que se materializa
        // aqui y se reinyecta mediante un decorador para que llegue al controlador.
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(CallHistoryWebFilter::drain)
                .defaultIfEmpty(new byte[0])
                .flatMap(requestBody -> {
                    AtomicReference<byte[]> responseBody = new AtomicReference<>(new byte[0]);
                    AtomicReference<Throwable> failure = new AtomicReference<>();

                    ServerWebExchange decorated = exchange.mutate()
                            .request(replayableRequest(exchange, requestBody))
                            .response(capturingResponse(exchange, responseBody))
                            .build();

                    return chain.filter(decorated)
                            .doOnError(failure::set)
                            .doFinally(signal -> save(decorated, calledAt, startNanos, requestBody,
                                    responseBody.get(), failure.get()));
                });
    }

    /** Repite el cuerpo ya leido para que el controlador lo reciba intacto. */
    private ServerHttpRequestDecorator replayableRequest(ServerWebExchange exchange, byte[] body) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return body.length == 0
                        ? Flux.empty()
                        : Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
    }

    /** Copia el cuerpo de la respuesta antes de enviarlo al cliente. */
    private ServerHttpResponseDecorator capturingResponse(ServerWebExchange exchange,
                                                          AtomicReference<byte[]> captured) {
        return new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return DataBufferUtils.join(Flux.from(body)).flatMap(joined -> {
                    byte[] bytes = drain(joined);
                    captured.set(bytes);
                    return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
                });
            }
        };
    }

    private void save(ServerWebExchange exchange, Instant calledAt, long startNanos,
                      byte[] requestBody, byte[] responseBody, Throwable failure) {

        var request = exchange.getRequest();
        var status = exchange.getResponse().getStatusCode();
        Integer statusCode = status != null ? status.value() : null;
        boolean failed = failure != null || (statusCode != null && statusCode >= 400);
        boolean selfReferential = request.getPath().value().startsWith(HISTORY_PATH);

        String body = selfReferential && !failed
                ? OMITTED_BODY
                : truncate(new String(responseBody, StandardCharsets.UTF_8));

        String errorMessage = null;
        if (failure != null) {
            errorMessage = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } else if (failed) {
            errorMessage = body;
        }

        callHistoryService.recordAsync(new CallHistory(
                calledAt,
                request.getPath().value(),
                request.getMethod().name(),
                describeParameters(exchange, requestBody),
                failed ? null : body,
                errorMessage,
                statusCode,
                (System.nanoTime() - startNanos) / 1_000_000));
    }

    /** Query params y cuerpo recibido, en una sola cadena legible. */
    private String describeParameters(ServerWebExchange exchange, byte[] requestBody) {
        var parts = new StringBuilder();
        var query = exchange.getRequest().getQueryParams();
        if (!query.isEmpty()) {
            parts.append("query=").append(query);
        }
        if (requestBody.length > 0) {
            if (!parts.isEmpty()) {
                parts.append(' ');
            }
            parts.append("body=").append(truncate(new String(requestBody, StandardCharsets.UTF_8)));
        }
        return parts.isEmpty() ? null : parts.toString();
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_CAPTURED_CHARS) {
            return value;
        }
        return value.substring(0, MAX_CAPTURED_CHARS) + "...[truncado]";
    }

    /** Extrae los bytes de un buffer y lo libera. */
    private static byte[] drain(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }
}
