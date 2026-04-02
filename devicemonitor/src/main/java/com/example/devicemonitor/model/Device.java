package com.example.devicemonitor.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.processing.Generated;
import java.time.Instant;

/**
 * Represents a single network device being monitored by the system.
 * This class is both a JPA Entity (maps to a database table) and the JSON
 * request/response body used by the REST API.
 *
 * CONCEPT — JPA (Java Persistence API) & ORM:
 * JPA is a standard that lets you work with database rows as Java objects.
 * Instead of writing SQL like "INSERT INTO devices (name, ip) VALUES (?, ?)",
 * you just call repository.save(device). The JPA provider (Hibernate, used here)
 * translates your object operations into SQL automatically. This pattern is called
 * ORM — Object-Relational Mapping.
 */
@Entity  // Marks this class as a JPA entity — Hibernate will create a "devices" table for it
@Table(name= "devices")  // Explicitly names the DB table "devices" (default would be "device")
public class Device {

    /**
     * The unique identifier for this device in the database.
     *
     * CONCEPT — Primary Keys & Auto-Generation:
     * @Id marks this field as the primary key (unique row identifier) in the table.
     * @GeneratedValue(strategy=GenerationType.IDENTITY) tells the database to
     * auto-increment this value (1, 2, 3, ...) whenever a new row is inserted.
     * We use Long (object) instead of long (primitive) so the value can be null
     * before the record is saved to the DB (null means "not yet persisted").
     */
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    /**
     * The human-readable name of the device (e.g., "Workstation-01").
     *
     * CONCEPT — Bean Validation:
     * @NotBlank triggers validation when this object is received in a POST/PUT
     * request body. If name is null, empty, or only whitespace, Spring will
     * reject the request with a 400 Bad Request before it even reaches the
     * controller method. The message is returned in the error response.
     */
    @NotBlank(message = "Device name is required")
    private String name;

    /**
     * The IPv4 address of the device (e.g., "192.168.1.10").
     * Uniqueness is enforced in DeviceService.registerDevice(), not at the DB level.
     */
    @NotBlank(message = "IP address is required")
    private String ipAddress;

    /**
     * The current connectivity state of the device.
     *
     * CONCEPT — @Enumerated(EnumType.STRING):
     * By default, JPA stores enums as integers (0, 1, 2...) based on their
     * declaration order. This is fragile — reordering the enum breaks all
     * existing data. EnumType.STRING stores the name ("ONLINE", "OFFLINE",
     * "UNKNOWN") instead, making the data self-documenting and order-independent.
     */
    @Enumerated(EnumType.STRING)
    private DeviceStatus status;

    /**
     * Timestamp of when the device's status was last updated.
     *
     * CONCEPT — Instant vs LocalDateTime:
     * Instant represents a point in time on the UTC timeline with no timezone.
     * It's the preferred choice for storing timestamps in backend systems because
     * it's unambiguous — there's no "what timezone is this?" confusion.
     * LocalDateTime, by contrast, has no timezone info and can cause bugs across
     * regions or daylight-saving-time boundaries.
     */
    private Instant lastSeenAt;

    // ----------------------------
    // Constructors
    // ----------------------------

    /**
     * No-argument constructor required by JPA.
     *
     * CONCEPT — Why JPA needs a no-arg constructor:
     * When Hibernate loads a row from the database, it creates a Device object
     * using reflection (Device.class.newInstance()), then calls the setters.
     * This requires a public or protected no-arg constructor to exist.
     * Without it, JPA will throw an exception at startup.
     */
    public Device() {
    }

    /**
     * Convenience constructor for creating a new device with a name and IP.
     * Sets status to UNKNOWN and lastSeenAt to the current time by default.
     *
     * @param name      human-readable device name
     * @param ipAddress IPv4 address string
     */
    public Device(String name, String ipAddress){
        this.name = name;
        this.ipAddress = ipAddress;
        this.status = DeviceStatus.UNKNOWN;  // New devices haven't been health-checked yet
        this.lastSeenAt = Instant.now();     // Record creation time
    }

    // ----------------------------
    // Getters and Setters
    // ----------------------------
    // CONCEPT — Encapsulation:
    // Fields are private so outside code cannot directly modify them (e.g., device.id = 5).
    // All access goes through these methods, giving this class control over its own state.
    // JPA also uses these methods (or direct field access) to read/write values.

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