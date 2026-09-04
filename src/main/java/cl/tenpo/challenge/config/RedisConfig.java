package cl.tenpo.challenge.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;

/**
 * Conexion a Redis para el rate limiting distribuido.
 *
 * <p>Se usa Lettuce directamente en vez de Spring Data Redis: lo unico que hace
 * falta es la conexion asincrona que consume Bucket4j.
 */
@Configuration
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(AppProperties properties) {
        return RedisClient.create(properties.rateLimit().redisUrl());
    }

    /**
     * ProxyManager asincrono: las operaciones devuelven {@code CompletableFuture},
     * de modo que el filtro nunca bloquea el event loop esperando a Redis.
     *
     * <p>Las claves expiran poco despues del periodo de recarga, para no dejar
     * buckets huerfanos en Redis.
     */
    @Bean
    AsyncProxyManager<byte[]> bucketProxyManager(RedisClient redisClient, AppProperties properties) {
        Duration ttl = properties.rateLimit().period().plusMinutes(1);
        return Bucket4jLettuce.casBasedBuilder(redisClient)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(ttl))
                .build()
                .asAsync();
    }
}
