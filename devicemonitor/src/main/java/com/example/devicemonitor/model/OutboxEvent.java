package com.example.devicemonitor.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity that implements the <em>Transactional Outbox</em> pattern for
 * reliable, at-least-once event delivery.
 *
 * <h2>Why an outbox?</h2>
 * <p>When a device is created, updated, or checked, the application needs to
 * both persist the change to the {@code devices} table AND notify downstream
 * consumers (e.g. an event bus or message broker). Doing these two things in
 * separate transactions risks losing the notification if the process crashes
 * between the database write and the broker publish.
 *
 * <p>The outbox pattern solves this by writing the event record into the
 * {@code outbox_events} table <em>within the same database transaction</em>
 * as the device mutation. A separate relay process (not yet implemented)
 * then reads {@code PENDING} rows and forwards them to the broker, marking
 * each row {@code PROCESSED} on success or {@code FAILED} on error. This
 * guarantees that no event is silently dropped.
 *
 * <h2>Relationship to {@link DeviceEvent}</h2>
 * <p>{@code DeviceEvent} is an in-memory, non-persistent representation of
 * what happened. {@code OutboxEvent} is its durable counterpart: it stores a
 * serialized {@code payload} (typically JSON-encoded from the {@code DeviceEvent})
 * and tracks delivery lifecycle via the {@link Status} enum.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /**
     * Delivery lifecycle state for an outbox row.
     *
     * <p>The relay process transitions rows from {@code PENDING} to either
     * {@code PROCESSED} or {@code FAILED}. Rows in {@code FAILED} may be
     * retried or dead-lettered depending on operational policy.
     */
    public enum Status {
        /** The event has been written but not yet forwarded to the broker. */
        PENDING,

        /** The event was successfully delivered to the broker. */
        PROCESSED,

        /**
         * Delivery was attempted but failed (e.g. broker unavailable).
         * {@code processedAt} is still set to record when the failure
         * occurred, even though delivery did not succeed.
         */
        FAILED
    }

    /**
     * Auto-incremented primary key. Rows are typically processed in ascending
     * ID order to preserve approximate event ordering within each device.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Foreign key reference to the {@link Device} that triggered this event.
     * Stored as a plain {@code Long} (not a {@code @ManyToOne} join) to keep
     * the outbox table decoupled from the devices table — the relay process
     * needs only the ID to route the event, not the full entity.
     */
    private Long deviceId;

    /**
     * The category of change that produced this event.
     * Stored as a string for the same reason as {@link Device#status}:
     * readability and resilience to enum reordering.
     *
     * @see DeviceEvent.EventType
     */
    @Enumerated(EnumType.STRING)
    private DeviceEvent.EventType eventType;

    /**
     * Serialized event data, typically a JSON string built from the
     * corresponding {@link DeviceEvent}. Storing raw JSON here means the
     * relay process can forward it directly without re-querying the device,
     * even if the device record has since changed.
     */
    private String payload;

    /**
     * Current delivery state of this outbox row.
     * The relay process filters on {@code status = 'PENDING'} to find
     * undelivered events.
     */
    @Enumerated(EnumType.STRING)
    private Status status;

    /**
     * UTC timestamp recorded when the outbox row was first inserted.
     * Useful for SLA monitoring (e.g. alerting if events stay PENDING too long)
     * and for ordering events when multiple rows share the same device.
     */
    private Instant createdAt;

    /**
     * UTC timestamp recorded when the relay process last attempted delivery
     * (regardless of whether it succeeded or failed). {@code null} until the
     * first delivery attempt occurs.
     */
    private Instant processedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification.
     * Not intended for direct use in application code.
     */
    public OutboxEvent(){}

    /**
     * Creates a new outbox event in the {@link Status#PENDING} state.
     *
     * <p>The {@code createdAt} timestamp is captured at construction time,
     * and {@code processedAt} is left {@code null} until a delivery attempt
     * is made. This constructor is called by the service layer immediately
     * after (or within the same transaction as) a device mutation.
     *
     * @param deviceId  ID of the device that was mutated
     * @param eventType category of change (created, updated, deleted, etc.)
     * @param payload   serialized event body (typically JSON) to forward to consumers
     */
    public OutboxEvent(Long deviceId, DeviceEvent.EventType eventType, String payload){
        this.deviceId = deviceId;
        this.eventType = eventType;
        this.payload = payload;
        // All new outbox rows start PENDING — they are only transitioned by the relay process.
        this.status = Status.PENDING;
        // Record insertion time for ordering and SLA tracking purposes.
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Getters (no setters — state transitions go through dedicated methods below)
    // -------------------------------------------------------------------------

    /** Returns the database-assigned primary key. */
    public Long getId() { return id; }

    /** Returns the ID of the device that triggered this event. */
    public Long getDeviceId() { return deviceId; }

    /** Returns the type of event that was recorded. */
    public DeviceEvent.EventType getEventType() { return eventType; }

    /** Returns the serialized payload (typically JSON) to be forwarded by the relay. */
    public String getPayload() { return payload; }

    /** Returns the current delivery status of this outbox row. */
    public Status getStatus() { return status; }

    /** Returns the UTC instant when this row was inserted. */
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Returns the UTC instant when delivery was last attempted, or {@code null}
     * if no delivery attempt has been made yet.
     */
    public Instant getProcessedAt() { return processedAt; }

    // -------------------------------------------------------------------------
    // State-transition methods
    // -------------------------------------------------------------------------

    /**
     * Marks this outbox event as successfully delivered.
     *
     * <p>Sets {@link Status#PROCESSED} and records the current time as
     * {@code processedAt}. Called by the relay process after confirming that
     * the broker has accepted the event.
     */
    public void markProcessed() {
        this.status = Status.PROCESSED;
        // Capture when delivery completed for audit and latency analysis.
        this.processedAt = Instant.now();
    }

    /**
     * Marks this outbox event as having failed delivery.
     *
     * <p>Sets {@link Status#FAILED} and records the current time as
     * {@code processedAt} so operators can see when the failure occurred.
     * The relay process or an ops tool can inspect {@code FAILED} rows
     * and decide whether to retry or discard them.
     */
    public void markFailed() {
        this.status = Status.FAILED;
        // Record the failure time even though delivery did not succeed —
        // useful for diagnosing how long the relay was stuck before failing.
        this.processedAt = Instant.now();
    }
}
