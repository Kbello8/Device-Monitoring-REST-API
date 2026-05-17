package com.example.devicemonitor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Application-wide exception handler that translates domain and validation
 * exceptions into structured HTTP error responses.
 *
 * <p>{@code @RestControllerAdvice} is a composed annotation that combines
 * {@code @ControllerAdvice} (applies to all {@code @Controller} beans) with
 * {@code @ResponseBody} (serialises the return value as JSON). This means every
 * handler method here automatically produces a JSON body — no need to annotate
 * each method individually.</p>
 *
 * <p>Centralising error handling here keeps controller methods clean: they only
 * describe the happy path, and any exception propagates up to this class for
 * uniform formatting. All error responses share the same JSON shape:
 * {@code {"error": "...", "timestamp": "..."}} so API consumers have a
 * consistent contract regardless of which error occurred.</p>
 *
 * <p><strong>Note:</strong> There is also an empty {@link DeviceNotFoundExceptionHandler}
 * class at the bottom of this file that is annotated with {@code @RestControllerAdvice}
 * but contains no handler methods. That class is effectively dead code and can
 * be safely removed.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles {@link DeviceNotFoundException}, which is thrown by
     * {@code DeviceService} whenever a lookup by ID finds no matching row.
     *
     * <p>Maps to HTTP {@code 404 Not Found} — the semantically correct status
     * when a resource identified by a URI does not exist. The exception's
     * message (e.g. {@code "Device not found with id: 42"}) is forwarded
     * verbatim so the caller knows exactly which ID was missing.</p>
     *
     * @param ex the caught exception, carrying the formatted "not found" message
     * @return a {@code 404} response whose JSON body contains {@code "error"}
     *         (the exception message) and {@code "timestamp"} (current UTC time)
     */
    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDeviceNotFoundException(DeviceNotFoundException ex) {
        // Build an immutable map with two keys and return it as the response body.
        // Map.of() is used (Java 9+) for conciseness; it is unordered but that is
        // acceptable here since JSON object key order is not semantically significant.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map
                .of("error", ex.getMessage(),
                        "timestamp", Instant.now().toString()));
    }

    /**
     * Handles {@link IllegalArgumentException}, thrown by service-layer business
     * rule checks — for example, when a caller attempts to register a device
     * with an IP address that already belongs to another device.
     *
     * <p>Maps to HTTP {@code 400 Bad Request} because the client supplied data
     * that violates a business constraint, not a system fault. Returning 400
     * (rather than 409 Conflict) is a deliberate simplification: all "invalid
     * input" cases are collapsed into one status code to keep the handler
     * surface small.</p>
     *
     * @param ex the caught exception, whose message describes the violated rule
     * @return a {@code 400} response whose JSON body contains {@code "error"}
     *         and {@code "timestamp"}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map
                .of("error", ex.getMessage(),
                        "timestamp", Instant.now().toString()));
    }

    /**
     * Handles {@link MethodArgumentNotValidException}, which Spring MVC throws
     * automatically when a request body annotated with {@code @Valid} (or
     * {@code @Validated}) fails Bean Validation constraints (e.g., {@code @NotBlank}
     * on {@code Device.name} or {@code Device.ipAddress}).
     *
     * <p>Maps to HTTP {@code 400 Bad Request} — the client sent data that does
     * not satisfy the declared field-level constraints.</p>
     *
     * <p><strong>Known issue / latent bug:</strong> The {@code message} local
     * variable below collects per-field constraint violations into a human-readable
     * string (e.g. {@code "[name:must not be blank, ipAddress:must not be blank]"})
     * but is never actually used in the response body. The response currently
     * returns {@code ex.getMessage()} — the raw Spring MVC error text — instead
     * of the formatted field-level summary. This means validation error responses
     * are less helpful than intended. The fix is to replace {@code ex.getMessage()}
     * with {@code message} in the {@code Map.of()} call below.</p>
     *
     * @param ex the caught validation exception, containing one or more
     *           {@code FieldError}s describing which constraints were violated
     * @return a {@code 400} response whose JSON body contains {@code "error"}
     *         and {@code "timestamp"}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        // Extract all field-level constraint violations and format them as
        // "fieldName:constraintMessage" entries joined into a single string.
        // Example output: "[name:must not be blank, ipAddress:must not be blank]"
        // NOTE: this variable is built but not yet wired into the response body —
        // see the method-level JavaDoc for details.
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> e.getField() + ":" + e.getDefaultMessage())
                .toList()
                .toString();

        // TODO: replace ex.getMessage() with `message` to surface the per-field
        // validation summary instead of the raw Spring MVC exception text.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map
                .of("error", ex.getMessage(),
                        "timestamp", Instant.now().toString()));
    }
}

/**
 * Empty {@code @RestControllerAdvice} class with no handler methods.
 *
 * <p>This class is effectively dead code — it registers itself as a controller
 * advice bean but intercepts nothing. It was likely a stub or leftover from a
 * refactor. It can be safely deleted without any behavioural change; Spring
 * will simply have one fewer advice bean to scan.</p>
 */
@RestControllerAdvice
class DeviceNotFoundExceptionHandler {

}
