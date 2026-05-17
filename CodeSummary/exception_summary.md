# Exception Package Summary

**Package:** `com.example.devicemonitor.exception`
**Location:** `devicemonitor/src/main/java/com/example/devicemonitor/exception/`

---

## Purpose

This package centralises all error-handling concerns for the Device Monitoring REST API. It contains two pieces that work together: a typed domain exception that signals a missing device, and a global handler that intercepts exceptions thrown anywhere in the application and converts them into structured JSON HTTP error responses. Keeping these concerns here (rather than scattered across controllers or services) means the rest of the codebase only has to throw; formatting and status-code selection happen in one place.

---

## Files

### 1. `DeviceNotFoundException.java`

**Responsibility:** Represents the domain-specific "not found" condition for a device lookup.

`DeviceNotFoundException` extends `RuntimeException` (unchecked), so callers in `DeviceService` can throw it without declaring it in method signatures. When a service method looks up a device by ID and finds nothing, it throws this exception with the missing ID embedded in the message (e.g. `"Device not found with id: 42"`). The exception propagates up through the controller and is caught by `GlobalExceptionHandler`, which converts it to an HTTP 404 before it ever reaches the client.

**Key design decision — unchecked exception:** Using an unchecked exception avoids polluting every intermediate method signature with `throws DeviceNotFoundException`. Spring's `@ExceptionHandler` mechanism is designed for this pattern: exceptions bubble up freely and are caught at the web boundary.

**Key design decision — dedicated type:** A named exception type (`DeviceNotFoundException`) rather than a generic `RuntimeException` or `NoSuchElementException` allows the handler to match on this exact type and return a semantically correct, domain-aware 404 response with no ambiguity.

---

### 2. `GlobalExceptionHandler.java`

**Responsibility:** Intercepts application exceptions and maps them to uniform JSON HTTP error responses.

Annotated with `@RestControllerAdvice`, which applies to all `@Controller` beans in the application context and automatically serialises return values as JSON. Three handler methods are registered:

#### `handleDeviceNotFoundException(DeviceNotFoundException ex)` → HTTP 404

Called when `DeviceService` cannot locate a device by ID. Returns a `404 Not Found` response. The exception message (containing the missing ID) is forwarded verbatim so the API consumer knows exactly which resource was absent.

#### `handleBadRequest(IllegalArgumentException ex)` → HTTP 400

Called when service-layer business rules are violated — the primary case being a duplicate IP address check in `DeviceService.registerDevice`. Returns a `400 Bad Request`. Using 400 (rather than 409 Conflict) is a simplification that collapses all "invalid input" cases into one status code.

#### `handleValidation(MethodArgumentNotValidException ex)` → HTTP 400

Called automatically by Spring MVC when a request body annotated with `@Valid` fails Bean Validation constraints (e.g., `@NotBlank` on `Device.name` or `Device.ipAddress`). Returns a `400 Bad Request`.

**Known bug in this handler:** The method assembles a per-field violation summary string (format: `"[fieldName:constraintMessage, ...]"`) but never uses it — the response body currently returns `ex.getMessage()` (the raw Spring MVC exception text) instead. The fix is to replace `ex.getMessage()` with the local `message` variable in the `Map.of()` call.

#### `DeviceNotFoundExceptionHandler` (empty stub class)

A second `@RestControllerAdvice`-annotated class at the bottom of the file. It contains no handler methods and is dead code. It can be deleted without any behavioural change.

---

## Uniform Error Response Shape

All three active handlers produce the same JSON structure:

```json
{
  "error": "<exception message>",
  "timestamp": "<UTC instant, ISO-8601>"
}
```

`Map.of()` (Java 9+) is used for conciseness. The map is unordered, but JSON object key order carries no semantic meaning for API consumers.

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| `@RestControllerAdvice` on a single class | One place to read/modify all error mappings; controllers stay focused on the happy path |
| Unchecked `RuntimeException` base class | No forced `throws` declarations at call sites; Spring's handler intercepts at the web boundary |
| Named domain exception type | Enables precise handler matching (`DeviceNotFoundException` → 404 only, not all `RuntimeException`s) |
| `Instant.now().toString()` in every response | Gives consumers a reference timestamp for correlating errors with server-side logs |
| `IllegalArgumentException` → 400 (not 409) | Simplification: all "bad client input" cases unified under one status code |

---

## Package Interactions

| This package ... | Interacts with ... | How |
|---|---|---|
| `DeviceNotFoundException` | `DeviceService` | Service throws it when `findById` returns empty |
| `DeviceNotFoundException` | `GlobalExceptionHandler` | Handler catches it and maps to 404 |
| `GlobalExceptionHandler` | `DeviceController` | Exceptions thrown by or propagated through the controller are intercepted here |
| `GlobalExceptionHandler` | `DeviceService` | `IllegalArgumentException` thrown by service business logic is caught here |
| `GlobalExceptionHandler` | Spring MVC internals | `MethodArgumentNotValidException` is generated by Spring's `@Valid` processing before the controller method even runs |
