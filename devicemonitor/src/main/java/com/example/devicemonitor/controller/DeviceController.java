package com.example.devicemonitor.controller;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.model.DeviceStatusSummary;
import com.example.devicemonitor.service.CacheService;
import com.example.devicemonitor.service.DeviceEventPublisher;
import com.example.devicemonitor.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Optional;

/**
 * REST controller that exposes all device management endpoints under the base path
 * {@code /api/devices}.
 *
 * <p>This is the outermost layer of the application's layered architecture
 * (Controller → Service → Repository → H2). Its sole responsibilities are:
 * <ul>
 *   <li>Deserializing and validating incoming HTTP requests.</li>
 *   <li>Delegating business logic to {@link DeviceService}.</li>
 *   <li>Serializing results into appropriate HTTP responses (status codes + JSON body).</li>
 * </ul>
 *
 * <p>The controller also exposes two lightweight observability endpoints — one for cache
 * statistics ({@link CacheService}) and one for the internal event-queue depth
 * ({@link DeviceEventPublisher}) — so operators can inspect infrastructure state without
 * hitting the database directly.
 *
 * <p>Error handling is NOT performed here. {@code DeviceNotFoundException} and
 * {@code IllegalArgumentException} bubble up to {@code GlobalExceptionHandler}, which
 * translates them into 404 and 400 responses respectively, keeping this class free of
 * try/catch boilerplate.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    // -----------------------------------------------------------------------
    // Dependencies (constructor-injected for testability and immutability)
    // -----------------------------------------------------------------------

    /** Core business logic for CRUD operations and health checks. */
    private final DeviceService deviceService;

    /**
     * Read-through / write-behind cache that sits in front of the database.
     * The controller references it directly only to expose the {@code /cache/stats}
     * observability endpoint; all caching logic lives in {@link DeviceService}.
     */
    private final CacheService cacheService;

    /**
     * In-memory queue that broadcasts device lifecycle events (CREATED / UPDATED /
     * DELETED) to downstream consumers. Referenced here only to expose the
     * {@code /queue/stats} observability endpoint.
     */
    private final DeviceEventPublisher deviceEventPublisher;

    /**
     * Constructs the controller with its required collaborators.
     *
     * <p>Constructor injection is preferred over field injection because it makes
     * dependencies explicit, enables final fields, and simplifies unit testing
     * (no reflection or Spring context needed to instantiate the class in tests).
     *
     * @param deviceService        the service layer handling all device business logic
     * @param cacheService         the cache abstraction used for the stats endpoint
     * @param deviceEventPublisher the event queue used for the queue-stats endpoint
     */
    public DeviceController(DeviceService deviceService, CacheService cacheService, DeviceEventPublisher deviceEventPublisher) {
        this.deviceService = deviceService;
        this.cacheService = cacheService;
        this.deviceEventPublisher = deviceEventPublisher;
    }

    // -----------------------------------------------------------------------
    // CRUD Endpoints
    // -----------------------------------------------------------------------

    /**
     * Registers a new device in the system.
     *
     * <p><b>POST /api/devices</b>
     *
     * <p>The request body must contain at least {@code name} and {@code ipAddress}.
     * {@code @Valid} triggers Bean Validation on the {@link Device} entity before the
     * method body executes; if validation fails, Spring throws
     * {@code MethodArgumentNotValidException} which {@code GlobalExceptionHandler}
     * maps to a 400 response with field-level error details.
     *
     * <p>The service layer enforces IP-address uniqueness and always initialises
     * {@code status} to {@code UNKNOWN} regardless of what the caller sends, so callers
     * do not need to supply those fields.
     *
     * @param device the device payload from the request body (must pass Bean Validation)
     * @return 201 Created with the persisted {@link Device} (including its generated id)
     */
    @PostMapping
    public ResponseEntity<Device> registerDevice(
            @Valid @RequestBody Device device) {

        // Delegate creation to the service, which handles uniqueness checks,
        // default-status assignment, cache population, and outbox event writing.
        Device created = deviceService.registerDevice(device);

        // Return 201 (not the default 200) to correctly signal resource creation.
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Returns cache statistics for the device cache layer.
     *
     * <p><b>GET /api/devices/cache/stats</b>
     *
     * <p>This is a lightweight observability endpoint — it does not touch the database.
     * The response tells operators how many devices are currently cached and what the
     * cache's time-to-live (TTL) policy is.
     *
     * <p>The TTL value (30 seconds) is documented here as a hard-coded constant because
     * it is a known property of the cache implementation; it is not fetched dynamically
     * from the cache itself.
     *
     * @return 200 OK with a JSON object containing {@code cachedDevices} (current entry
     *         count) and {@code ttlSeconds} (fixed eviction window)
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> getDeviceCacheStats() {
        // Map.of produces an immutable map — appropriate here because the response
        // is built fresh on every call and never mutated.
        return ResponseEntity.ok(Map.of(
                "cachedDevices", cacheService.size(),
                "ttlSeconds", 30
        ));
    }

    /**
     * Retrieves all registered devices, with an optional filter by status.
     *
     * <p><b>GET /api/devices</b><br>
     * <b>GET /api/devices?status=ONLINE</b> (or OFFLINE / UNKNOWN)
     *
     * <p>The {@code status} query parameter is optional. When absent, all devices are
     * returned. When present, only devices matching that {@link DeviceStatus} value are
     * included. Results are always sorted alphabetically by device name (enforced in the
     * service layer).
     *
     * <p>{@code Optional.ofNullable} is used to bridge Spring's nullable
     * {@code @RequestParam} into the service method's {@code Optional<DeviceStatus>}
     * signature, keeping the service API expressive without requiring the controller to
     * branch on null.
     *
     * @param status optional filter; valid values are {@code ONLINE}, {@code OFFLINE},
     *               {@code UNKNOWN}; omit the parameter to return all devices
     * @return 200 OK with a JSON array of matching {@link Device} objects (may be empty)
     */
    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices(
            @RequestParam(required = false) DeviceStatus status) {

        // Wrap the nullable status in an Optional so the service can use a
        // clean functional-style branch without null checks.
        List<Device> devices = deviceService.getAllDevices(Optional.ofNullable(status));
        return ResponseEntity.ok(devices);
    }

    /**
     * Retrieves a single device by its unique identifier.
     *
     * <p><b>GET /api/devices/{id}</b>
     *
     * <p>The service checks the cache first (read-through pattern). On a cache miss it
     * fetches from the database and repopulates the cache. If no device with the given
     * id exists, {@code DeviceNotFoundException} is thrown and {@code GlobalExceptionHandler}
     * returns a 404 response.
     *
     * @param id the database-generated identifier of the device
     * @return 200 OK with the matching {@link Device}, or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<Device> getDeviceBy(@PathVariable long id) {
        return ResponseEntity.ok(deviceService.getDeviceById(id));
    }

    /**
     * Partially updates an existing device.
     *
     * <p><b>PUT /api/devices/{id}</b>
     *
     * <p>Only fields present in the request body are applied; absent or blank fields
     * are ignored (patch-style semantics despite the HTTP verb being PUT). This is
     * handled in the service layer via null/blank guards, not here in the controller.
     *
     * <p>If the device does not exist, a 404 is returned. If the update triggers a
     * conflict (e.g., duplicate IP), an {@code IllegalArgumentException} produces a 400.
     *
     * <p>Note: the method is named {@code getAllDevices} here — this is a naming error
     * inherited from the original implementation. The method's behaviour is entirely
     * that of an update operation.
     *
     * @param id      the identifier of the device to update
     * @param updates a {@link Device} payload carrying the fields to change
     * @return 200 OK with the updated {@link Device}
     */
    @PutMapping("/{id}")
    public ResponseEntity<Device> getAllDevices(@PathVariable Long id,
                                                @RequestBody Device updates) {
        return ResponseEntity.ok(deviceService.updateDevice(id, updates));
    }

    /**
     * Deletes a device from the system.
     *
     * <p><b>DELETE /api/devices/{id}</b>
     *
     * <p>The service writes a {@code DEVICE_DELETED} outbox event in the same
     * transaction as the delete, ensuring the downstream event is never lost even if
     * the application crashes immediately after. The cache entry is also invalidated.
     *
     * <p>If the device does not exist, {@code DeviceNotFoundException} causes a 404.
     *
     * @param id the identifier of the device to remove
     * @return 204 No Content on success (standard REST convention for successful deletes)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        deviceService.deleteDevice(id);
        // 204 No Content: the resource no longer exists, so there is nothing to return.
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Aggregation / Reporting Endpoints
    // -----------------------------------------------------------------------

    /**
     * Returns a summary of device counts grouped by status, ranked by device count.
     *
     * <p><b>GET /api/devices/summary</b>
     *
     * <p>The underlying query uses a native SQL {@code RANK() OVER (PARTITION BY ...)}
     * window function in the repository layer to compute rankings server-side, keeping
     * this endpoint efficient even with large device tables.
     *
     * <p>Each entry in the response carries the status label, total device count for
     * that status, and the rank relative to other statuses.
     *
     * @return 200 OK with a JSON array of {@link DeviceStatusSummary} objects
     */
    @GetMapping("/summary")
    public ResponseEntity<List<DeviceStatusSummary>> getStatusSummary() {
        return ResponseEntity.ok(deviceService.getDeviceStatusSummary());
    }

    // -----------------------------------------------------------------------
    // Health Check Endpoints
    // -----------------------------------------------------------------------

    /**
     * Asynchronously checks the health (reachability) of a single device.
     *
     * <p><b>POST /api/devices/{id}/health-check</b>
     *
     * <p>The method returns a {@link CompletableFuture} rather than a plain
     * {@code ResponseEntity}. Spring MVC (via its async-request support) keeps the
     * HTTP connection open without blocking a servlet thread while the simulated ping
     * runs. Once the future completes, Spring serialises the result and sends the
     * response automatically.
     *
     * <p>The service simulates network latency (200–800 ms, random) and an 80%
     * probability of the device being {@code ONLINE}. The device's status and
     * {@code lastSeenAt} are persisted after each check.
     *
     * <p>This is a POST (not GET) because the operation has a side effect: it mutates
     * the device's status in the database.
     *
     * @param id the identifier of the device to ping
     * @return a future that resolves to 200 OK with the updated {@link Device},
     *         or 404 if the device does not exist
     */
    @PostMapping("/{id}/health-check")
    public CompletableFuture<ResponseEntity<Device>> checkHealth(@PathVariable long id) {
        // thenApply wraps the Device result in a ResponseEntity without blocking —
        // the transformation executes on the same thread that completes the future.
        return deviceService.checkDeviceHealth(id)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Synchronously checks the health of ALL registered devices in parallel.
     *
     * <p><b>POST /api/devices/health-check</b>
     *
     * <p>Unlike the single-device check, this endpoint is synchronous from the HTTP
     * perspective — it blocks until every device's simulated ping has resolved. The
     * service fans out one {@link CompletableFuture} per device, then calls
     * {@code CompletableFuture.allOf(...).join()} to wait for the full batch before
     * returning results.
     *
     * <p>Because all pings run concurrently, the wall-clock time is approximately
     * equal to the slowest single ping (up to ~800 ms) rather than the sum of all
     * ping durations.
     *
     * <p>This is a POST (not GET) because the operation has side effects: it mutates
     * the status and {@code lastSeenAt} of every device.
     *
     * @return 200 OK with a JSON array of all {@link Device} objects with refreshed
     *         statuses
     */
    @PostMapping("/health-check")
    public ResponseEntity<List<Device>> checkAllHealth() {
        return ResponseEntity.ok(deviceService.checkAllDevicesHealth());
    }

    // -----------------------------------------------------------------------
    // Observability Endpoints
    // -----------------------------------------------------------------------

    /**
     * Returns the current depth of the internal device-event queue.
     *
     * <p><b>GET /api/devices/queue/stats</b>
     *
     * <p>The {@link DeviceEventPublisher} maintains a {@code LinkedBlockingQueue} that
     * buffers lifecycle events (CREATED / UPDATED / DELETED) for asynchronous
     * downstream processing by {@code DeviceEventConsumer}. A growing queue depth can
     * indicate that the consumer is falling behind or has stalled.
     *
     * <p>This endpoint is intentionally lightweight — it reads a single atomic integer
     * from the queue and does not touch the database.
     *
     * @return 200 OK with a JSON object containing {@code pendingEvents} (number of
     *         events currently waiting in the queue)
     */
    @GetMapping("/queue/stats")
    public ResponseEntity<Map<String, Object>> getDeviceQueueStats() {
        return ResponseEntity.ok(Map.of(
                "pendingEvents", deviceEventPublisher.size()
        ));
    }
}
