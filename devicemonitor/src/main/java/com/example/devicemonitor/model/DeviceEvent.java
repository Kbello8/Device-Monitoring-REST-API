package com.example.devicemonitor.model;

import java.time.Instant;

/**
 * Immutable in-memory record of something that happened to a {@link Device}.
 *
 * <p>{@code DeviceEvent} captures the essential facts about a device lifecycle
 * transition at the moment it occurs: which device was affected, what kind of
 * change took place, what the device's name and status were at that instant,
 * and when it happened. All fields are {@code final} — events are facts about
 * the past and must not be modified after creation.
 *
 * <h2>Relationship to {@link OutboxEvent}</h2>
 * <p>This class is a transient, in-process value object. It is NOT persisted
 * directly. The service layer serializes a {@code DeviceEvent} (typically to
 * JSON) and stores the result as the {@code payload} field of an
 * {@link OutboxEvent}, which IS persisted. This separation keeps the
 * business-facing event model clean while the outbox handles durability
 * and delivery concerns.
 *
 * <h2>Event types</h2>
 * <p>Defined by the nested {@link EventType} enum. Four types are currently
 * recognized; new types can be added without changing the shape of
 * {@code DeviceEvent} itself.
 */
public class DeviceEvent {

    /**
     * Classifies the nature of the change that caused this event to be raised.
     *
     * <p>Consumers of the outbox (downstream services, audit logs, dashboards)
     * use the event type to route or filter events without having to inspect
     * the payload body.
     */
    public enum EventType {
        /** A new device was successfully registered in the system. */
        DEVICE_CREATED,

        /** An existing device's name, IP, or other attribute was modified. */
        DEVICE_UPDATED,

        /** A device was removed from the system. */
        DEVICE_DELETED,

        /**
         * A health-check cycle completed for the device.
         * The {@code status} field on the event reflects the outcome
         * ({@code ONLINE} or {@code OFFLINE}).
         */
        HEALTH_CHECK_COMPLETED
    }

    /** ID of the device this event is about. */
    private final Long deviceId;

    /** The type of change that produced this event. */
    private final EventType eventType;

    /**
     * Display name of the device at the time the event was raised.
     * Snapshotted here so downstream consumers have a human-readable label
     * even if the device is later renamed or deleted.
     */
    private final String deviceName;

    /**
     * Reachability status of the device at the moment the event occurred.
     * Particularly meaningful for {@link EventType#HEALTH_CHECK_COMPLETED}
     * events, where it carries the ping result ({@code ONLINE}/{@code OFFLINE}).
     * For {@link EventType#DEVICE_CREATED}, this will always be
     * {@link DeviceStatus#UNKNOWN}.
     */
    private final DeviceStatus status;

    /**
     * UTC instant at which the event was constructed.
     * Named {@code occuredAt} (note: one-'r' typo preserved from original
     * to avoid breaking any downstream consumers that may already reference
     * this field name in serialized form).
     */
    private final Instant occuredAt;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates an immutable device event capturing the current moment.
     *
     * <p>The {@code occuredAt} timestamp is set to {@link Instant#now()} at
     * construction time, meaning the event records the precise moment the
     * service layer decided something happened — not when it was later
     * serialized or stored.
     *
     * @param deviceId   ID of the affected device
     * @param eventType  category of change
     * @param deviceName display name of the device at the time of the event
     * @param status     reachability status of the device at the time of the event
     */
    public DeviceEvent(Long deviceId, EventType eventType, String deviceName, DeviceStatus status) {
        this.deviceId = deviceId;
        this.eventType = eventType;
        this.deviceName = deviceName;
        this.status = status;
        // Capture wall-clock time at construction so the event accurately reflects
        // when the business action occurred, independent of any async delays.
        this.occuredAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Accessors (no setters — this class is intentionally immutable)
    // -------------------------------------------------------------------------

    /** Returns the ID of the device this event is about. */
    public Long getDeviceId() { return deviceId;}

    /** Returns the type of change that produced this event. */
    public EventType getEventType() { return eventType;}

    /** Returns the device's display name as of the moment the event was created. */
    public String getDeviceName() { return deviceName;}

    /** Returns the device's reachability status as of the moment the event was created. */
    public DeviceStatus getStatus() {return status;}

    /**
     * Returns the UTC instant at which this event was constructed.
     * Note: field name contains a one-'r' typo ({@code occuredAt} vs
     * {@code occurredAt}) that is preserved intentionally to avoid breaking
     * existing serialized payloads.
     */
    public Instant getOccuredAt() { return occuredAt; }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    /**
     * Returns a compact, human-readable representation of the event.
     * Useful for logging and debugging — shows all key fields in a single line.
     *
     * <p>Example output:
     * <pre>
     *   DeviceEvent{id='42', type='HEALTH_CHECK_COMPLETED', name='Web Server 01', status='ONLINE', at='2024-03-15T10:30:00Z'}
     * </pre>
     */
    @Override
    public String toString() {
            return String.format("DeviceEvent{id='%d', type='%s', name='%s', status='%s', at='%s'}",
                    deviceId, eventType, deviceName, status, occuredAt);

    }
}
