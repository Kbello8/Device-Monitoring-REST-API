package com.example.devicemonitor.model;

import java.time.Instant;

public class DeviceEvent {

    public enum EventType {
        DEVICE_CREATED,
        DEVICE_UPDATED,
        DEVICE_DELETED,
        HEALTH_CHECK_COMPLETED
    }

    private final Long deviceId;
    private final EventType eventType;
    private final String deviceName;
    private final DeviceStatus status;
    private final Instant occuredAt;

    public DeviceEvent(Long deviceId, EventType eventType, String deviceName, DeviceStatus status) {
        this.deviceId = deviceId;
        this.eventType = eventType;
        this.deviceName = deviceName;
        this.status = status;
        this.occuredAt = Instant.now();
    }
        public Long getDeviceId() { return deviceId;}
        public EventType getEventType() { return eventType;}
        public String getDeviceName() { return deviceName;}
        public DeviceStatus getStatus() {return status;}
        public Instant getOccuredAt() { return occuredAt; }

    @Override
    public String toString() {
            return String.format("DeviceEvent{id='%d', type='%s', name='%s', status='%s', at='%s'}",
                    deviceId, eventType, deviceName, status, occuredAt);

    }
}
