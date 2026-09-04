package cl.tenpo.challenge.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import cl.tenpo.challenge.infra.history.CallHistory;
import cl.tenpo.challenge.infra.history.CallHistoryRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Unico punto de acceso al historial.
 *
 * <p>JPA es bloqueante, asi que <strong>todas</strong> las operaciones se publican en
 * {@link Schedulers#boundedElastic()}: el event loop de Netty nunca queda bloqueado
 * esperando a la base de datos. Es el precio de conservar JPA en una app WebFlux;
 * la alternativa 100% no bloqueante seria R2DBC (ver README, decisiones tecnicas).
 */
@Service
public class CallHistoryService {

    private static final Logger log = LoggerFactory.getLogger(CallHistoryService.class);

    private final CallHistoryRepository repository;

    public CallHistoryService(CallHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Persiste un registro sin afectar al request en curso: se ejecuta fuera de la
     * cadena de respuesta y un fallo al guardar jamas rompe la llamada del cliente.
     */
    public void recordAsync(CallHistory entry) {
        Mono.fromCallable(() -> repository.save(entry))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error("No se pudo registrar la llamada en el historial: {}",
                        error.getMessage()))
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }

    public Mono<Page<CallHistory>> findAll(Pageable pageable) {
        return Mono.fromCallable(() -> repository.findAll(pageable))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
