package com.example.devicemonitor.exception;

/**
 * Thrown when a device lookup by ID finds no matching record in the database.
 * Caught by GlobalExceptionHandler and converted into an HTTP 404 response.
 *
 * CONCEPT — Custom Exceptions:
 * Java's built-in exceptions (like IllegalArgumentException or NullPointerException)
 * are generic. Creating a custom exception gives you:
 *   1. Semantic clarity — "DeviceNotFoundException" tells you exactly what went wrong
 *   2. Targeted handling — GlobalExceptionHandler can catch THIS specific type and
 *      return a 404, while other exceptions get different HTTP status codes
 *   3. Consistent error messages — the message format ("Device not found with id: X")
 *      is defined once here, not scattered across the codebase
 *
 * CONCEPT — RuntimeException vs Exception:
 * Java has two categories of exceptions:
 *   - Checked exceptions (extends Exception): callers are FORCED by the compiler to
 *     either catch them or declare them with "throws". Useful for recoverable errors
 *     where the caller should explicitly handle failure (e.g., FileNotFoundException).
 *   - Unchecked exceptions (extends RuntimeException): callers are NOT forced to
 *     handle them. They propagate up the call stack automatically until something
 *     catches them (here, GlobalExceptionHandler). This is the standard choice for
 *     application-layer errors in Spring REST APIs — it keeps service and controller
 *     code clean without try/catch boilerplate everywhere.
 */
public class DeviceNotFoundException extends RuntimeException {

    /**
     * Creates the exception with a message identifying which device ID was not found.
     * The message is passed to RuntimeException, which stores it and makes it available
     * via getMessage() — which GlobalExceptionHandler uses to build the error response.
     *
     * @param id the ID that was searched for but not found
     */
    public DeviceNotFoundException(Long id) {
        super("Device not found with id: " + id);  // e.g., "Device not found with id: 42"
    }
}
