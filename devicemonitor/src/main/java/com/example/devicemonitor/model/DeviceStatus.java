package com.example.devicemonitor.model;

/**
 * Enumeration of all possible reachability states for a {@link Device}.
 *
 * <p>The health-check subsystem in {@code DeviceService} resolves each device
 * to one of these three states after attempting to ping its IP address.
 * The enum is stored in the database as its name string (via
 * {@code @Enumerated(EnumType.STRING)}) so the column remains readable and
 * is immune to reordering of enum constants.
 *
 * <p><b>State lifecycle:</b>
 * <pre>
 *   Registration ──► UNKNOWN
 *        │
 *        └─► Health check runs
 *                  │
 *              ping OK ──► ONLINE
 *              ping KO ──► OFFLINE
 * </pre>
 *
 * <p>A device stays {@code UNKNOWN} until its first health-check cycle
 * completes, which prevents the API from reporting misleading status
 * information before any connectivity test has been performed.
 */
public enum DeviceStatus {

    /**
     * The device responded successfully to the most recent health-check ping.
     * {@code lastSeenAt} is updated to the current time whenever a device
     * transitions to or stays in this state.
     */
    ONLINE,

    /**
     * The device did not respond to the most recent health-check ping.
     * {@code lastSeenAt} is NOT updated when a device is determined to be
     * offline, so it continues to reflect the last time the device was
     * actually reachable.
     */
    OFFLINE,

    /**
     * The device has been registered but has not yet undergone a health check.
     * This is the initial state assigned to every new device in
     * {@link Device#Device(String, String)} so that consumers can distinguish
     * "never checked" from "checked and found offline".
     */
    UNKNOWN
}
