package cl.tenpo.challenge.domain;

import java.math.BigDecimal;

import reactor.core.publisher.Mono;

/**
 * Puerto hacia el servicio externo de porcentaje. La implementacion real vive en
 * {@code infra.percentage}; los tests inyectan una implementacion determinista.
 */
public interface PercentageProvider {

    /**
     * @return el porcentaje a aplicar (por ejemplo {@code 10.5} para un 10,5%),
     *         o un error {@link cl.tenpo.challenge.domain.exception.PercentageServiceUnavailableException}
     *         si el servicio no responde tras agotar los reintentos.
     */
    Mono<BigDecimal> getPercentage();
}
