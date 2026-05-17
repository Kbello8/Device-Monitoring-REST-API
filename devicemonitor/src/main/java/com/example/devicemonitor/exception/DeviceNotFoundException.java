package com.example.devicemonitor.exception;

/**
 * Exception thrown when a requested device cannot be found in the data store.
 *
 * <p>Extends {@link RuntimeException} (unchecked) so callers are not forced to
 * declare or catch it at every call site — Spring's {@code @ExceptionHandler}
 * mechanism in {@link GlobalExceptionHandler} intercepts it at the web layer
 * and converts it into an HTTP 404 response before it ever reaches the client.</p>
 *
 * <p>Using a dedicated exception type (rather than a generic
 * {@code RuntimeException} or {@code NoSuchElementException}) lets the
 * exception handler match on this specific type and return a semantically
 * correct, domain-aware error message without ambiguity.</p>
 */
public class DeviceNotFoundException extends RuntimeException {

    /**
     * Constructs a new {@code DeviceNotFoundException} for the given device ID.
     *
     * <p>The message is formatted as {@code "Device not found with id: <id>"} and
     * is forwarded to {@link RuntimeException} via {@code super()}, making it
     * available through {@link Throwable#getMessage()} for logging and for the
     * error response body assembled by {@link GlobalExceptionHandler}.</p>
     *
     * @param id the numeric identifier of the device that could not be located;
     *           included in the exception message to aid debugging and to give
     *           the API consumer a precise, actionable error description
     */
    public DeviceNotFoundException(Long id) {
        // Delegate to RuntimeException with a human-readable message that
        // embeds the missing ID. GlobalExceptionHandler will extract this
        // message and surface it in the JSON error body returned to the caller.
        super("Device not found with id: " + id);
    }
}
