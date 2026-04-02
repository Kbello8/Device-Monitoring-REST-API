package com.example.devicemonitor.model;

/**
 * Represents the possible connectivity states of a monitored device.
 *
 * CONCEPT — Enums (Enumerations):
 * An enum is a special Java type that restricts a variable to one of a fixed set
 * of named constants. Instead of using raw strings like "online" or "ONLINE" (which
 * are error-prone — a typo compiles fine), an enum makes invalid states impossible
 * to represent at compile time.
 *
 * Example benefit:
 *   device.setStatus("ONLNE");  // typo — compiles, causes a silent bug
 *   device.setStatus(DeviceStatus.ONLNE); // typo — COMPILE ERROR, caught immediately
 *
 * CONCEPT — Where this enum is used:
 * - Stored in the database as a STRING (configured via @Enumerated(EnumType.STRING)
 *   in Device.java), so the DB column holds "ONLINE", "OFFLINE", or "UNKNOWN"
 *   rather than an integer index (which would be fragile if the enum order changed).
 * - Used as an optional filter parameter in GET /api/devices?status=ONLINE
 * - Set by DeviceService during health checks and device registration
 */
public enum DeviceStatus {
    ONLINE,   // Device responded to the simulated ping
    OFFLINE,  // Device did not respond to the simulated ping
    UNKNOWN   // Default state — device has not been health-checked yet
}
