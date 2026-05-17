package com.example.devicemonitor.service;

import com.example.devicemonitor.model.DeviceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * DeviceEventPublisher acts as an in-process, thread-safe event bus for
 * {@link DeviceEvent} objects produced by {@link DeviceService}.
 *
 * <h2>Design rationale</h2>
 * <p>Rather than calling downstream consumers synchronously (which would couple
 * them to every write operation and increase request latency), DeviceService
 * drops events into this publisher's queue and returns immediately. The
 * companion {@link DeviceEventConsumer} drains the queue on a dedicated
 * background thread, keeping event processing fully decoupled from the
 * HTTP request/response cycle.</p>
 *
 * <h2>Queue choice</h2>
 * <p>{@link LinkedBlockingQueue} is used because it is unbounded by default and
 * provides blocking {@code poll} semantics that let the consumer sleep
 * efficiently while the queue is empty — no busy-waiting required. The queue
 * is effectively unbounded here (no capacity argument is passed), which trades
 * memory safety for zero-drop behaviour under normal load. If back-pressure is
 * needed in the future, a bounded capacity can be passed to the constructor and
 * the {@code warn} log in {@link #publish} will fire when the limit is hit.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link LinkedBlockingQueue} is internally synchronised; no external locking
 * is needed for individual {@code offer}/{@code poll} calls.</p>
 */
@Service
public class DeviceEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(DeviceEventPublisher.class);

    /**
     * The underlying in-memory queue. {@link LinkedBlockingQueue} is chosen over
     * {@code ArrayBlockingQueue} because we do not want a hard capacity limit by
     * default — events should never be silently dropped under normal operation.
     * The {@code offer} call is non-blocking and returns false only if a capacity
     * limit were set (none is set here, so it will always succeed barring an OOM).
     */
    // Thread-safe bounded queue - max 1000 events
    private final LinkedBlockingQueue<DeviceEvent> queue =
            new LinkedBlockingQueue<>();

    /**
     * Enqueues a {@link DeviceEvent} for asynchronous processing.
     *
     * <p>Uses {@link LinkedBlockingQueue#offer} (non-blocking) instead of
     * {@code put} (blocking) so that the calling thread — typically a
     * request-handling thread in DeviceService — is never blocked if the queue
     * were ever capacity-bounded. If the offer is rejected (capacity full), a
     * warning is logged and {@code false} is returned so the caller can decide
     * how to handle the drop.</p>
     *
     * @param event the device lifecycle event to publish; must not be {@code null}.
     * @return {@code true} if the event was accepted; {@code false} if the queue
     *         was full and the event was dropped.
     */
    public boolean publish(DeviceEvent event) {
        boolean accepted = queue.offer(event);
        // Log a warning when an event is dropped so ops can detect back-pressure
        // issues without needing to instrument the queue directly.
        if (!accepted) {
            logger.warn("Event queue full - dropped event for device{}",
                    event.getDeviceId());
        }
        return accepted;
    }

    /**
     * Blocking poll with a timeout — intended to be called by
     * {@link DeviceEventConsumer} in its drain loop.
     *
     * <p>Blocking with a timeout (rather than a spin loop) lets the consumer
     * thread sleep while the queue is empty and wake up promptly when a new
     * event arrives, without burning CPU cycles. The timeout also serves as a
     * "heartbeat" interval: the consumer wakes up every {@code timeoutMs}
     * milliseconds even without an event, allowing it to check its {@code running}
     * flag and shut down cleanly.</p>
     *
     * @param timeoutMs maximum time to wait for an event, in milliseconds.
     * @return the next {@link DeviceEvent}, or {@code null} if the timeout
     *         elapsed before one became available.
     * @throws InterruptedException if the waiting thread is interrupted, which
     *         the consumer uses as a shutdown signal.
     */
    public DeviceEvent poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the current number of events waiting in the queue.
     *
     * <p>Useful for monitoring and health-check endpoints to detect whether
     * the consumer is keeping up with the producer.</p>
     *
     * @return the instantaneous queue depth (may change immediately after return).
     */
    public int size() {
        return queue.size();
    }
}
