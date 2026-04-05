package com.example.devicemonitor.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status {
        PENDING,
        PROCESSED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    private DeviceEvent.EventType eventType;

    private String payload;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant createdAt;

    private Instant processedAt;

    public OutboxEvent(){}

    public OutboxEvent(Long deviceId, DeviceEvent.EventType eventType, String payload){
        this.deviceId = deviceId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public DeviceEvent.EventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    public void markProcessed() {
        this.status = Status.PROCESSED;
        this.processedAt = Instant.now();
    }

    public void markFailed() {
        this.status = Status.FAILED;
        this.processedAt = Instant.now();
    }
}
