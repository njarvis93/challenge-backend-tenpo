package cl.tenpo.challenge.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion propia de la aplicacion, bajo el prefijo {@code app}.
 */
@ConfigurationProperties("app")
public record AppProperties(Percentage percentage, Mock mock, RateLimit rateLimit) {

    /**
     * Servicio externo que entrega el porcentaje a aplicar.
     *
     * @param maxAttempts intentos totales (1 original + N reintentos)
     */
    public record Percentage(String url, Duration timeout, int maxAttempts, Duration initialBackoff) {
    }

    /**
     * Mock del servicio externo. Devuelve un porcentaje aleatorio dentro del rango
     * para que el sistema se ejercite de verdad; un valor fijo no probaria nada.
     *
     * @param failureRate probabilidad [0..1] de responder 500, para demostrar los reintentos
     * @param seed        semilla opcional; si viene, el aleatorio es reproducible
     */
    public record Mock(boolean enabled, BigDecimal minPercentage, BigDecimal maxPercentage,
                       double failureRate, Long seed) {
    }

    /**
     * Rate limiting global (token bucket compartido en Redis).
     */
    public record RateLimit(boolean enabled, long capacity, Duration period, String key, String redisUrl) {
    }
}
