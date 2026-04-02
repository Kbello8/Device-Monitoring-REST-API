package com.example.devicemonitor.controller;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.model.DeviceStatusSummary;
import com.example.devicemonitor.service.DeviceService;
import jakarta.validation.Valid;
import org.apache.coyote.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Optional;

/**
 * REST API controller exposing all device monitoring endpoints under /api/devices.
 * This class is responsible ONLY for handling HTTP — parsing requests and formatting
 * responses. All business logic lives in DeviceService.
 *
 * CONCEPT — @RestController:
 * A combination of @Controller + @ResponseBody. @Controller registers this class as
 * a Spring MVC controller. @ResponseBody tells Spring to serialize the return value
 * of every method directly to the HTTP response body as JSON (using Jackson),
 * rather than treating it as a view name to render.
 *
 * CONCEPT — @RequestMapping("/api/devices"):
 * All endpoints in this class are prefixed with /api/devices. Individual method
 * annotations (@GetMapping, @PostMapping, etc.) define sub-paths and HTTP methods.
 *
 * CONCEPT — REST (Representational State Transfer):
 * REST uses HTTP methods to express intent:
 *   POST   → create a new resource
 *   GET    → read a resource
 *   PUT    → replace/update a resource
 *   DELETE → remove a resource
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * Constructor injection of DeviceService. Spring resolves and injects the bean.
     * See DataSeeder for a detailed explanation of constructor injection.
     */
    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * POST /api/devices
     * Registers a new device in the system.
     *
     * CONCEPT — @RequestBody:
     * Tells Spring to deserialize the HTTP request body (JSON) into a Device object.
     * Jackson (the JSON library bundled with Spring Boot) handles the conversion:
     *   {"name": "Server-01", "ipAddress": "10.0.0.1"} → Device object with those fields set
     *
     * CONCEPT — @Valid:
     * Triggers Bean Validation on the Device object before this method runs.
     * If Device's @NotBlank constraints are violated, Spring throws
     * MethodArgumentNotValidException before reaching this method — caught by GlobalExceptionHandler.
     *
     * CONCEPT — 201 Created vs 200 OK:
     * POST creating a resource should return 201 Created (not 200) per REST conventions.
     * The response body contains the created resource with its server-assigned ID.
     *
     * @param device the device to register, deserialized from the request body
     * @return 201 Created with the saved Device in the response body
     */
    @PostMapping
    public ResponseEntity<Device> registerDevice(
            @Valid @RequestBody Device device) {
        Device created = deviceService.registerDevice(device);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * GET /api/devices
     * GET /api/devices?status=ONLINE
     * Returns all devices, optionally filtered by status.
     *
     * CONCEPT — @RequestParam(required = false):
     * Maps an HTTP query parameter to a method argument.
     * "required = false" means the parameter is optional — if not provided,
     * the argument is null. Optional.ofNullable(null) produces an empty Optional,
     * which the service interprets as "no filter."
     * Example: GET /api/devices?status=ONLINE → status = DeviceStatus.ONLINE
     * Example: GET /api/devices             → status = null → Optional.empty()
     *
     * CONCEPT — Spring automatically converts the string "ONLINE" to DeviceStatus.ONLINE
     * because DeviceStatus is an enum and Spring registers enum converters by default.
     *
     * @param status optional status filter (null if not provided)
     * @return 200 OK with list of devices
     */
    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices(
        @RequestParam(required = false) DeviceStatus status) {
        List<Device> devices = deviceService.getAllDevices(Optional.ofNullable(status));
        return ResponseEntity.ok(devices);
    }

    /**
     * GET /api/devices/{id}
     * Returns a single device by its database ID.
     *
     * CONCEPT — @PathVariable:
     * Binds a URI template variable to a method parameter.
     * GET /api/devices/5 → id = 5
     * If the device isn't found, DeviceService throws DeviceNotFoundException,
     * which GlobalExceptionHandler catches and converts to a 404 response.
     *
     * @param id the device ID from the URL path
     * @return 200 OK with the matching Device
     */
    @GetMapping("/{id}")
    public ResponseEntity<Device> getDeviceBy(@PathVariable long id) {
        return ResponseEntity.ok(deviceService.getDeviceById(id));
    }

    /**
     * PUT /api/devices/{id}
     * Partially updates a device — only provided fields are changed.
     *
     * Note: method is named getAllDevices which is a copy-paste error — it should
     * be named updateDevice. The behavior is correct despite the misleading name.
     *
     * @param id      device ID from the URL path
     * @param updates request body containing fields to update (null fields ignored)
     * @return 200 OK with the updated Device
     */
    @PutMapping("/{id}")
    public ResponseEntity<Device> getAllDevices(@PathVariable Long id,
                                                @RequestBody Device updates) {
        return ResponseEntity.ok(deviceService.updateDevice(id, updates));
    }

    /**
     * DELETE /api/devices/{id}
     * Removes a device from the system.
     *
     * CONCEPT — 204 No Content:
     * DELETE requests that succeed should return 204 No Content — the operation
     * succeeded, but there's nothing meaningful to return in the body.
     * ResponseEntity<Void> explicitly signals there is no body.
     *
     * @param id device ID from the URL path
     * @return 204 No Content on success
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        deviceService.deleteDevice(id);
        return ResponseEntity.noContent().build();  // 204 with empty body
    }

    /**
     * GET /api/devices/summary
     * Returns device counts grouped by status, ranked by frequency.
     *
     * @return 200 OK with list of DeviceStatusSummary (status, count, rank)
     */
    @GetMapping("/summary")
    public ResponseEntity<List<DeviceStatusSummary>> getStatusSummary() {
        return ResponseEntity.ok(deviceService.getDeviceStatusSummary());
    }

    /**
     * POST /api/devices/{id}/health-check
     * Asynchronously pings a single device and updates its status.
     * The HTTP response is sent when the ping completes (non-blocking for the server thread).
     *
     * CONCEPT — Returning CompletableFuture from a controller:
     * When a Spring MVC controller method returns CompletableFuture<ResponseEntity<T>>,
     * Spring recognizes it as async and releases the HTTP request thread immediately.
     * When the CompletableFuture completes (the ping finishes), Spring sends the response.
     * This allows the server to handle other requests while waiting for the ping.
     *
     * CONCEPT — Method reference ResponseEntity::ok:
     * thenApply(ResponseEntity::ok) is shorthand for thenApply(device -> ResponseEntity.ok(device)).
     * It wraps the completed Device in a 200 OK ResponseEntity when the future resolves.
     *
     * @param id device ID to health-check
     * @return CompletableFuture that resolves to 200 OK with the updated Device
     */
    @PostMapping("/{id}/health-check")
    public CompletableFuture<ResponseEntity<Device>> checkHealth(@PathVariable long id) {
        return deviceService.checkDeviceHealth(id)
                .thenApply(ResponseEntity::ok);  // wrap result in 200 OK when ping completes
    }

    /**
     * POST /api/devices/health-check
     * Pings ALL devices concurrently and waits for all results before responding.
     * This endpoint blocks until every device ping completes.
     *
     * Unlike the single-device health check above, this is synchronous from the
     * caller's perspective — it returns a normal ResponseEntity, not a CompletableFuture.
     * The concurrency happens inside DeviceService.checkAllDevicesHealth().
     *
     * @return 200 OK with the full list of devices and their updated statuses
     */
    @PostMapping("/health-check")
    public ResponseEntity<List<Device>> checkAllHealth() {
        return ResponseEntity.ok(deviceService.checkAllDevicesHealth());
    }
}
