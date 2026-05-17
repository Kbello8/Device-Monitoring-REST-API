package com.example.devicemonitor.service;

import com.example.devicemonitor.model.DeviceEvent;
import com.example.devicemonitor.model.OutboxEvent;
import com.example.devicemonitor.repository.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DeviceEventConsumer is the async consumer side of the internal event bus.
 *
 * <h2>Responsibility</h2>
 * <p>It drains {@link DeviceEvent} objects from the {@link DeviceEventPublisher}
 * queue and correlates each event with a matching {@link OutboxEvent} record in
 * the database. Once correlated, it is responsible for dispatching the event to
 * a downstream system (currently simulated; SQS dispatch is planned via
 * {@link SqsEventPublisher}).</p>
 *
 * <h2>Transactional Outbox pattern</h2>
 * <p>The system uses the <em>Transactional Outbox</em> pattern to guarantee
 * at-least-once event delivery without distributed transactions:</p>
 * <ol>
 *   <li>{@link DeviceService} writes a device change AND an {@link OutboxEvent}
 *       row in the same database transaction.</li>
 *   <li>This consumer reads events from the in-memory queue and looks for a
 *       matching PENDING outbox row.</li>
 *   <li>If found, it dispatches the event downstream and (future work) marks the
 *       outbox row PROCESSED so it is not retried.</li>
 * </ol>
 * <p>Because both the device record and the outbox record are written atomically,
 * a crash between step 1 and step 3 still leaves a PENDING row that a recovery
 * process (or a restart) can pick up — no event is silently lost.</p>
 *
 * <h2>Threading model</h2>
 * <p>A single background thread (created via {@link Executors#newSingleThreadExecutor})
 * runs the drain loop. One thread is sufficient because event processing is
 * expected to be fast (a DB lookup plus a lightweight dispatch call), and a
 * single thread avoids ordering anomalies that could arise if multiple threads
 * processed events concurrently.</p>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #init()} — starts the drain loop after Spring has wired all
 *       dependencies ({@code @PostConstruct}).</li>
 *   <li>{@link #stop()} — signals the loop to exit and shuts down the executor
 *       gracefully ({@code @PreDestroy}), waiting up to 5 seconds for the
 *       in-flight event to finish before forcing termination.</li>
 * </ul>
 */
@Service
public class DeviceEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DeviceEventConsumer.class);

    /** Source of events — shared queue between producer (DeviceService) and this consumer. */
    private final DeviceEventPublisher publisher;

    /** Used to correlate in-memory events with durable outbox records. */
    private final OutboxEventRepository outboxEventRepository;

    /**
     * Single-threaded executor that owns the drain loop. Using a dedicated thread
     * rather than the common ForkJoinPool isolates event processing from request
     * threads and prevents a slow downstream call from starving other work.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Cooperative shutdown flag. Declared {@code volatile} so that the write in
     * {@link #stop()} is immediately visible to the drain loop running on the
     * executor thread without needing explicit synchronisation.
     */
    private volatile boolean running = true;

    /**
     * @param publisher              the in-process event queue to drain.
     * @param outboxEventRepository  JPA repository for reading and updating
     *                               {@link OutboxEvent} records.
     */
    public DeviceEventConsumer(DeviceEventPublisher publisher, OutboxEventRepository outboxEventRepository) {
        this.publisher = publisher;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Starts the background drain loop.
     *
     * <p>Called by Spring after the bean is fully constructed. Submitting the
     * loop to the executor here (rather than in the constructor) ensures that
     * all injected dependencies are ready before the loop begins polling.</p>
     */
    @PostConstruct
    public void init() {
        executor.submit(this::consumeLoop);
        logger.info("Device Event Consumer started");
    }

    /**
     * Shuts down the drain loop gracefully on application shutdown.
     *
     * <p>Sets {@code running = false} so the loop exits after its current
     * iteration. Then waits up to 5 seconds for the executor to terminate
     * cleanly (allowing any in-flight {@link #processEvent} call to finish).
     * If the timeout elapses, {@code shutdownNow()} is called to interrupt
     * the thread immediately — at that point any unprocessed event will remain
     * in the database as a PENDING outbox record for future recovery.</p>
     */
    @PreDestroy
    public void stop() {
        // Signal the drain loop to exit on its next iteration
        running = false;
        executor.shutdown();
        try {
            // Give in-flight processing a grace period before forcibly stopping
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            // Re-interrupt the current thread so callers can detect the interruption
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Device Event Consumer stopped");
    }

    /**
     * The main drain loop executed on the background executor thread.
     *
     * <p>Polls the publisher queue with a 500 ms timeout. The timeout is
     * intentionally short so that the {@code running} flag is checked at least
     * twice per second, giving the application a responsive shutdown window.
     * If an {@link InterruptedException} is received, the thread's interrupt
     * status is restored and the loop exits — this handles the case where
     * {@link ExecutorService#shutdownNow()} interrupts the thread directly.</p>
     *
     * <p>Any other {@link Exception} from {@link #processEvent} is caught and
     * logged so that a single bad event cannot crash the entire consumer loop.</p>
     */
    private void consumeLoop() {
        while (running) {
            try {
                // Block for up to 500 ms; returns null if no event arrives in time.
                // This avoids a busy-wait spin while still checking `running` regularly.
                DeviceEvent event = publisher.poll(500);
                if (event != null) {
                    processEvent(event);
                }
            } catch (InterruptedException e) {
                // Restore the interrupted status so the executor can clean up correctly,
                // then break out of the loop.
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log but do not rethrow — a processing error on one event should not
                // kill the consumer for all subsequent events.
                logger.error("Error processing event", e);
            }
        }
    }

    /**
     * Processes a single {@link DeviceEvent} by correlating it with a PENDING
     * {@link OutboxEvent} in the database and dispatching it downstream.
     *
     * <h2>Correlation logic</h2>
     * <p>All PENDING outbox events are fetched and then filtered in-memory to
     * find one whose {@code deviceId} and {@code eventType} match the incoming
     * in-memory event. This approach keeps the query simple (no dynamic WHERE
     * clause) at the cost of fetching more rows than strictly necessary — an
     * acceptable trade-off for current scale. At higher volumes this should be
     * replaced with a targeted query such as
     * {@code findByDeviceIdAndEventTypeAndStatus}.</p>
     *
     * <h2>Downstream dispatch (TODO)</h2>
     * <p>The actual dispatch call is currently simulated with a log statement.
     * The intent (indicated by the TODO comment) is to inject {@link SqsEventPublisher}
     * here and call {@code sqsEventPublisher.publish(event)} so that events flow
     * to AWS SQS when the "aws" profile is active. If dispatch fails, the outbox
     * row is marked {@code FAILED} and saved so that monitoring and retry jobs
     * can identify and replay it.</p>
     *
     * @param event the in-memory event to process; never {@code null}.
     */
    private void processEvent(DeviceEvent event) {
        logger.info("Processing event: {}", event);

        // Look up the matching outbox record to confirm durable intent exists
        // before attempting downstream dispatch. The stream filter combines
        // deviceId AND eventType to narrow to exactly the right outbox entry.
        OutboxEvent outboxEvent = outboxEventRepository
                .findByStatus(OutboxEvent.Status.PENDING)
                .stream()
                .filter(e -> e.getDeviceId().equals(event.getDeviceId())
                        && e.getEventType().equals(event.getEventType()))
                .findFirst()
                .orElse(null);

        if (outboxEvent != null) {
            try {
                // TODO: Replace this stub with a real downstream call, e.g.:
                //   sqsEventPublisher.publish(event);
                // For now, log the dispatch so the flow is visible in local dev.
                logger.info("Dispatching to downstream: {}", event);

                // Future: mark outboxEvent.markProcessed() here on success
            } catch (Exception e) {
                // Mark the outbox row FAILED so it can be retried or alerted on.
                outboxEvent.markFailed();
                logger.error("Error processing event {}", event.getDeviceId(), e);
            }
            // Persist the updated outbox status (FAILED) regardless of outcome
            // so the record accurately reflects the last processing attempt.
            outboxEventRepository.save(outboxEvent);
        }
    }
}
