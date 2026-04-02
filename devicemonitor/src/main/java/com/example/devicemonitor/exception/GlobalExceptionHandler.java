package com.example.devicemonitor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Centralized error handler for the entire REST API.
 * Intercepts exceptions thrown anywhere in the controller layer and converts
 * them into structured JSON HTTP responses with appropriate status codes.
 *
 * CONCEPT — @RestControllerAdvice:
 * Without this class, any unhandled exception in a controller would result in
 * Spring returning a generic 500 Internal Server Error with an HTML error page.
 * @RestControllerAdvice is a global interceptor — it watches all @RestController
 * classes and steps in whenever a matching exception is thrown, allowing you to:
 *   1. Return a consistent JSON error format to API consumers
 *   2. Map different exception types to different HTTP status codes
 *   3. Keep controllers clean — they throw exceptions, this class handles them
 *
 * CONCEPT — Separation of Concerns:
 * Controllers should not contain try/catch blocks for business errors. The service
 * throws a semantic exception (DeviceNotFoundException), and this class is the
 * single place responsible for translating exceptions into HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles the case where a device ID lookup returns no result.
     * Returns HTTP 404 Not Found.
     *
     * CONCEPT — @ExceptionHandler:
     * This annotation tells Spring: "whenever a DeviceNotFoundException propagates
     * up from a controller, call this method instead of crashing."
     * The exception object is injected as a parameter automatically.
     *
     * CONCEPT — ResponseEntity:
     * ResponseEntity is a wrapper that lets you control both the HTTP status code
     * AND the response body. ResponseEntity.status(404).body(data) gives you full
     * control, as opposed to just returning a plain object (which defaults to 200 OK).
     *
     * CONCEPT — Map.of():
     * Creates an immutable Map with the given key-value pairs. Here it produces:
     * { "error": "Device not found with id: 5", "timestamp": "2024-01-01T..." }
     * This is the JSON body that the API consumer receives.
     */
    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDeviceNotFoundException(DeviceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map
                .of("error", ex.getMessage(),           // the message from DeviceNotFoundException
                        "timestamp", Instant.now().toString()));  // when the error occurred (UTC)
    }

    /**
     * Handles business rule violations — specifically, duplicate IP address registration.
     * Returns HTTP 400 Bad Request.
     *
     * IllegalArgumentException is a standard Java unchecked exception used here to
     * signal that the caller passed invalid input (an IP that already exists).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map
                .of("error", ex.getMessage(),
                        "timestamp", Instant.now().toString()));
    }

    /**
     * Handles validation failures from @NotBlank and other Bean Validation constraints
     * on the Device request body. Returns HTTP 400 Bad Request.
     *
     * CONCEPT — MethodArgumentNotValidException:
     * When Spring receives a POST/PUT request body and the object fails @NotBlank
     * (or other @Valid constraints), it throws MethodArgumentNotValidException
     * before the controller method even executes. This handler catches that.
     *
     * CONCEPT — BindingResult & FieldErrors:
     * The exception contains a BindingResult — a detailed report of all validation
     * failures. getFieldErrors() returns one error per violated constraint.
     * The stream below joins them into a readable string like:
     *   "[name:Device name is required, ipAddress:IP address is required]"
     * That string is returned directly in the error response body, giving the API
     * consumer a clear list of exactly which fields failed and why.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        // Build a human-readable summary of which fields failed and why
        // e.g., "[name:Device name is required, ipAddress:IP address is required]"
        String message = ex.getBindingResult()
                .getFieldErrors()              // get all field-level validation errors
                .stream()                      // treat the list as a stream for processing
                .map(e -> e.getField() + ":" + e.getDefaultMessage())  // format each error
                .toList()
                .toString();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map
                .of("error", message,          // return the field-level summary, not the raw Spring message
                        "timestamp", Instant.now().toString()));
    }
}