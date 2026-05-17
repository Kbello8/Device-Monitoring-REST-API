package com.example.devicemonitor.repository;

import com.example.devicemonitor.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link OutboxEvent} entities.
 *
 * <p>This repository is part of the Transactional Outbox pattern implementation.
 * The outbox pattern solves the dual-write problem: when a service needs to both
 * persist a domain change (e.g., device registration) AND publish an external event
 * (e.g., to a message broker), doing those two writes atomically is non-trivial.
 * The solution is to write the event to an {@code outbox} table in the same database
 * transaction as the domain change, then have a separate process (a poller or
 * change-data-capture consumer) relay the event outward. This guarantees the event
 * is never lost — if the relay process fails, the row stays in the outbox and can
 * be retried.</p>
 *
 * <p>Extends {@link JpaRepository}, giving this repository the full set of standard
 * CRUD operations (save, findById, findAll, delete, count, etc.) with no
 * implementation code required. Spring generates a proxy at startup based on the
 * interface alone.</p>
 *
 * <p>The generic parameters {@code <OutboxEvent, Long>} specify the managed entity
 * ({@code OutboxEvent}) and its primary key type ({@code Long}).</p>
 *
 * <p>The {@code @Repository} annotation registers this interface as a Spring bean
 * and enables Spring's persistence-exception translation so that raw JPA/JDBC
 * exceptions are converted to Spring's {@code DataAccessException} hierarchy before
 * reaching the service layer.</p>
 *
 * <p>Interacts with: any service component (e.g., an outbox poller or event publisher)
 * that needs to query for unprocessed events and mark them as sent or failed.</p>
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Retrieves all outbox events whose {@code status} field matches the given value.
     *
     * <p>Spring Data derives the SQL automatically from the method name:
     * {@code findBy} signals a SELECT query, and {@code Status} maps to the
     * {@code status} column on the outbox events table. The generated query is
     * effectively {@code SELECT * FROM outbox_events WHERE status = ?}.</p>
     *
     * <p>The primary use case is polling for pending events: a scheduler or background
     * thread calls this method with {@link OutboxEvent.Status#PENDING} to retrieve all
     * events that have not yet been forwarded to an external system. After successfully
     * publishing an event, the caller updates its status to {@link OutboxEvent.Status#SENT}
     * (or a failure status) and saves it back via {@code save()}, so it will no longer
     * appear in future PENDING queries.</p>
     *
     * <p>Returning {@code List} rather than a streaming or paginated type is acceptable
     * here because the outbox table is expected to remain small — events are consumed
     * and status-updated quickly by the relay process, so the PENDING backlog should
     * not grow unbounded under normal operation. If high-throughput scenarios are
     * anticipated, pagination ({@code findByStatus(Status, Pageable)}) would be
     * preferable to avoid loading a large result set into memory at once.</p>
     *
     * @param status the {@link OutboxEvent.Status} to filter by (e.g., PENDING, SENT)
     * @return a (possibly empty) list of outbox events with the given status,
     *         in no guaranteed order
     */
    List<OutboxEvent> findByStatus(OutboxEvent.Status status);
}
