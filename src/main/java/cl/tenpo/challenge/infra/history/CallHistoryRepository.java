package cl.tenpo.challenge.infra.history;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA (bloqueante). Todo acceso pasa por
 * {@link cl.tenpo.challenge.domain.CallHistoryService}, que lo aisla en un
 * scheduler elastico para no bloquear el event loop de Netty.
 */
public interface CallHistoryRepository extends JpaRepository<CallHistory, Long> {
}
