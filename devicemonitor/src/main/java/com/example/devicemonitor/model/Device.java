package com.example.devicemonitor.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.processing.Generated;
import java.time.Instant;

@Entity
@Table(name= "devices")
public class Device {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Device name is required")
    private String name;

    @NotBlank(message = "IP address is required")
    private String ipAddress;

    @Enumerated (EnumType.STRING)
    private DeviceStatus status;

    private Instant lastSeenAt;

    // Constructors
    public Device() {
    }

    public Device(String name, String ipAddress){
        this.name = name;
        this.ipAddress = ipAddress;
        this.status = DeviceStatus.UNKNOWN;
        this.lastSeenAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus status) { this.status = status; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

}