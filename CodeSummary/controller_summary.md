# Controller Package Summary

## Package Purpose

`com.example.devicemonitor.controller`

The controller package is the HTTP entry point for the Device Monitoring REST API. It translates incoming HTTP requests into calls to the service layer and translates service responses back into HTTP responses (status codes + JSON bodies). No business logic lives here — the controller's only jobs are request validation, delegation, and response formatting.

All endpoints are mounted under the base path `/api/devices` and return JSON automatically via Spring's `@RestController` (which combines `@Controller` + `@ResponseBody`).

---

## Files

### `DeviceController.java`

The single controller class in this package. It declares ten endpoints spanning four concerns: CRUD operations, health checks, aggregation/reporting, and infrastructure observability.

**Dependencies (constructor-injected):**

| Field | Type | Why |
|---|---|---|
| `deviceService` | `DeviceService` | Handles all business logic |
| `cacheService` | `CacheService` | Referenced only to expose cache stats |
| `deviceEventPublisher` | `DeviceEventPublisher` | Referenced only to expose queue depth |

Constructor injection is used throughout — not field injection — because it makes dependencies explicit, keeps fields `final`, and avoids Spring context requirements in unit tests.

---

## Endpoint Reference

### CRUD

| Method | Path | Handler | Response |
|---|---|---|---|
| POST | `/api/devices` | `registerDevice` | 201 + created `Device` |
| GET | `/api/devices` | `getAllDevices` | 200 + `List<Device>` |
| GET | `/api/devices/{id}` | `getDeviceBy` | 200 + `Device` |
| PUT | `/api/devices/{id}` | `getAllDevices` (misnamed) | 200 + updated `Device` |
| DELETE | `/api/devices/{id}` | `deleteDevice` | 204 No Content |

### Health Checks

| Method | Path | Handler | Response |
|---|---|---|---|
| POST | `/api/devices/{id}/health-check` | `checkHealth` | `CompletableFuture<ResponseEntity<Device>>` |
| POST | `/api/devices/health-check` | `checkAllHealth` | 200 + `List<Device>` |

### Aggregation / Reporting

| Method | Path | Handler | Response |
|---|---|---|---|
| GET | `/api/devices/summary` | `getStatusSummary` | 200 + `List<DeviceStatusSummary>` |

### Observability

| Method | Path | Handler | Response |
|---|---|---|---|
| GET | `/api/devices/cache/stats` | `getDeviceCacheStats` | 200 + `{cachedDevices, ttlSeconds}` |
| GET | `/api/devices/queue/stats` | `getDeviceQueueStats` | 200 + `{pendingEvents}` |

---

## Key Design Decisions

### 1. No error handling in the controller

There are no try/catch blocks anywhere in `DeviceController`. Exceptions from the service layer (`DeviceNotFoundException` → 404, `IllegalArgumentException` → 400, `MethodArgumentNotValidException` → 400) are intercepted by `GlobalExceptionHandler` (`@RestControllerAdvice`). This keeps the controller lean and avoids duplicating error-formatting logic across endpoints.

### 2. Bean Validation via `@Valid`

`registerDevice` applies `@Valid` to the request body. This triggers Jakarta Bean Validation annotations on the `Device` entity (`@NotBlank` on `name` and `ipAddress`) before the method body runs. Failed validation is caught by `GlobalExceptionHandler` and returned as a structured 400 with field-level messages.

### 3. Async vs. synchronous health checks

The single-device health check (`checkHealth`) returns `CompletableFuture<ResponseEntity<Device>>`. Spring MVC recognises this return type and releases the servlet thread immediately, keeping the thread pool free during the simulated ping latency (200–800 ms). Spring resumes the response automatically when the future completes.

The bulk health check (`checkAllHealth`) returns a plain `ResponseEntity<List<Device>>`. The service layer fans out futures internally and joins them with `CompletableFuture.allOf(...).join()` before returning, so the controller sees it as a normal synchronous call. Wall-clock time is bounded by the single slowest ping (~800 ms) rather than the sum of all pings.

The choice to use POST (not GET) for both health check endpoints is intentional: each check mutates the device's `status` and `lastSeenAt` in the database, making them unsafe and non-idempotent by REST semantics.

### 4. Patch semantics over a PUT verb

`PUT /api/devices/{id}` behaves like a partial update (PATCH): only non-null, non-blank fields from the request body are applied. The null/blank guards live in `DeviceService.updateDevice`, not in the controller. This is a pragmatic trade-off — the controller is simpler, but the HTTP verb signals "replace" semantics to clients. A future improvement would be to either rename the verb to PATCH or enforce full-replacement semantics.

### 5. Method naming bug in `updateDevice`

The PUT handler method is named `getAllDevices`, colliding in name (but not in route) with the GET handler. This is a pre-existing bug in the original code. It has been documented in the JavaDoc comment rather than silently renamed, so the next developer who touches this file can rename it in a tracked commit.

### 6. Observability endpoints bypass the service

`getDeviceCacheStats` and `getDeviceQueueStats` access `CacheService` and `DeviceEventPublisher` directly, bypassing `DeviceService`. This is by design: these are infrastructure-health reads, not business operations. Routing them through the service would add a pointless indirection layer.

### 7. `Optional.ofNullable` as a null bridge

`getAllDevices` uses `Optional.ofNullable(status)` to convert Spring's nullable `@RequestParam` into the `Optional<DeviceStatus>` expected by the service. This avoids a null check in the controller and keeps the service API idiomatic.

---

## Notable Methods

### `registerDevice`

Accepts a validated `Device` body, delegates to `DeviceService.registerDevice`, and returns 201. The service enforces IP uniqueness, forces `status=UNKNOWN`, writes an outbox event, populates the cache, and publishes to the in-memory event queue — all transparent to the controller.

### `checkHealth`

Returns `CompletableFuture<ResponseEntity<Device>>`, enabling Spring's async MVC support. Uses `thenApply(ResponseEntity::ok)` to wrap the service result in a response body without blocking. This is one of the more sophisticated patterns in the codebase: the controller never calls `get()` or `join()` on the future itself.

### `checkAllHealth`

Appears synchronous from the HTTP perspective but achieves concurrency internally. The service uses `CompletableFuture.allOf` to fan out pings and gather results, so every device is pinged in parallel. This makes the endpoint useful for large fleets without requiring async handling at the controller level.

### `getStatusSummary`

Delegates to a native SQL window-function query (`RANK() OVER (PARTITION BY ...)`) in the repository layer. The controller is unaware of this implementation detail — it just receives and forwards a `List<DeviceStatusSummary>`.

---

## Interactions With Other Packages

| Package | Interaction |
|---|---|
| `service.DeviceService` | Primary collaborator. Every CRUD and health-check endpoint delegates directly to it. |
| `service.CacheService` | Read via `size()` for the `/cache/stats` observability endpoint only. |
| `service.DeviceEventPublisher` | Read via `size()` for the `/queue/stats` observability endpoint only. |
| `model.Device` | Used as both the request body type (deserialization) and the response body type (serialization). |
| `model.DeviceStatus` | Used as a typed `@RequestParam` on `GET /api/devices`; Spring converts the string query param automatically. |
| `model.DeviceStatusSummary` | Response type for `GET /api/devices/summary`. |
| `exception.GlobalExceptionHandler` | Handles all exceptions thrown by the service layer; the controller never catches exceptions itself. |
