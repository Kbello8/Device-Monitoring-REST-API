package com.example.devicemonitor.service;

import com.example.devicemonitor.exception.DeviceNotFoundException;
import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.model.DeviceStatusSummary;
import com.example.devicemonitor.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Business logic layer for all device operations.
 * This class sits between the controller (HTTP) and the repository (database),
 * and is the only place where business rules are enforced.
 *
 * CONCEPT — Service Layer / Layered Architecture:
 * The application is divided into three layers:
 *   Controller → Service → Repository
 * Each layer has one job:
 *   - Controller: parse HTTP requests, delegate to service, return HTTP responses
 *   - Service (this class): enforce business rules, orchestrate operations
 *   - Repository: read/write to the database
 *
 * This separation means business rules aren't duplicated in controllers, and
 * database logic doesn't leak into business logic. It also makes each layer
 * independently testable — the tests for this class mock the repository entirely.
 *
 * CONCEPT — @Service:
 * A specialization of @Component that marks this class as a service bean.
 * Functionally identical to @Component, but signals intent to other developers.
 */
@Service
public class DeviceService {

    // final = this reference cannot be reassigned after construction (immutable dependency)
    private final DeviceRepository repository;

    /**
     * Constructor injection — Spring automatically provides the DeviceRepository bean.
     * See DataSeeder for a detailed explanation of constructor injection and DI.
     */
    public DeviceService(DeviceRepository repository) {
        this.repository = repository;
    }

    // -------------------------------------------------------------------------
    // CREATE
    // -------------------------------------------------------------------------

    /**
     * Registers a new device in the system after validating there are no duplicate IPs.
     *
     * Business rules enforced here:
     *   1. IP address must be unique (enforced in code, not as a DB constraint)
     *   2. Status is always forced to UNKNOWN on creation — the caller cannot set it
     *   3. lastSeenAt is always set to now — the caller cannot set it
     *
     * @param device the device data from the request body (name and ipAddress expected)
     * @return the saved Device with its generated ID, status, and timestamp
     * @throws IllegalArgumentException if the IP address is already in use
     */
    public Device registerDevice(Device device) {
        // Check uniqueness before saving — repository has no DB-level unique constraint
        if (repository.existsByIpAddress(device.getIpAddress())) {
            throw new IllegalArgumentException("Device " + device.getIpAddress() + " already exists");
        }
        // Enforce server-side defaults regardless of what the client sent
        device.setStatus(DeviceStatus.UNKNOWN);
        device.setLastSeenAt(Instant.now());
        return repository.save(device);  // save() returns the persisted entity with the generated ID
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    /**
     * Returns all devices, optionally filtered by status, sorted alphabetically by name.
     *
     * CONCEPT — Optional<T>:
     * Optional is a container that either holds a value or is empty. It's used here
     * instead of passing null to indicate "no filter." This avoids NullPointerExceptions
     * and makes the "filter is absent" case explicit in the method signature.
     *
     * CONCEPT — Stream API:
     * Java Streams allow functional-style processing of collections:
     *   status.map(s -> repository.findByStatus(s)) — if status is present, call findByStatus
     *   .orElseGet(() -> repository.findAll())       — if status is empty, call findAll
     * Then .stream().sorted(...).toList() sorts the result before returning it.
     *
     * CONCEPT — Comparator.comparing():
     * Creates a Comparator that compares Device objects by their getName() return value.
     * Method reference Device::getName is shorthand for d -> d.getName().
     *
     * @param status if present, filter to only devices with this status; if empty, return all
     * @return list of devices sorted by name ascending
     */
    public List<Device> getAllDevices(Optional<DeviceStatus> status) {
        // Either filter by status or fetch all, depending on whether a filter was provided
        List<Device> devices = status
                .map(s -> repository.findByStatus(s))   // Optional is present: filter by status
                .orElseGet(() -> repository.findAll());  // Optional is empty: get all devices

        // Sort the result alphabetically by device name before returning
        return devices.stream()
                .sorted(Comparator.comparing(Device::getName))  // sort A→Z by name
                .toList();                                        // collect back to a List
    }

    /**
     * Looks up a single device by its database ID.
     *
     * CONCEPT — orElseThrow():
     * repository.findById() returns an Optional<Device>. If no device exists with
     * that ID, the Optional is empty. orElseThrow() unwraps the value if present,
     * or throws the provided exception if absent. This is cleaner than:
     *   Optional<Device> opt = repository.findById(id);
     *   if (opt.isEmpty()) throw new DeviceNotFoundException(id);
     *   return opt.get();
     *
     * @param id the database primary key to look up
     * @return the matching Device
     * @throws DeviceNotFoundException if no device exists with that ID
     */
    public Device getDeviceById(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    /**
     * Partially updates an existing device. Only non-null, non-blank fields in the
     * updates object are applied — fields not provided by the caller are left unchanged.
     *
     * CONCEPT — Partial Update Pattern (PATCH-style behavior on a PUT endpoint):
     * A full replacement (PUT) would overwrite all fields, requiring the client to
     * send every field even if only one changed. This partial approach only applies
     * fields that are present in the request, which is more flexible.
     *
     * CONCEPT — Optional.ofNullable().filter().ifPresent():
     * This chain avoids nested if-null checks:
     *   Optional.ofNullable(value)    — wraps value, handling null safely
     *   .filter(v -> !v.isBlank())    — only proceed if value is non-empty
     *   .ifPresent(setter)            — call the setter only if value passed the filter
     *
     * @param id      ID of the device to update
     * @param updates object containing fields to update (null fields are ignored)
     * @return the updated and re-saved Device
     * @throws DeviceNotFoundException if no device exists with that ID
     */
    public Device updateDevice(Long id, Device updates) {
        // Load the existing device first — throws if not found
        Device existingDevice = getDeviceById(id);

        // Only update name if the update contains a non-blank name
        Optional.ofNullable(updates.getName())
            .filter(name -> !name.isBlank())       // reject empty strings like ""
            .ifPresent(existingDevice::setName);   // apply if valid

        // Only update IP if the update contains a non-blank IP
        Optional.ofNullable(updates.getIpAddress())
                .filter(ip -> !ip.isBlank())
                .ifPresent(existingDevice::setIpAddress);

        // Only update status if the update contains a status value
        // Also updates lastSeenAt when status changes, to track when state changed
        Optional.ofNullable(updates.getStatus())
                .ifPresent(status -> {
                    existingDevice.setStatus(status);
                    existingDevice.setLastSeenAt(Instant.now());  // record when status changed
                });

        return repository.save(existingDevice);  // persist the merged result
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    /**
     * Deletes a device by ID. Throws if the device doesn't exist (no silent no-ops).
     *
     * @param id the database ID of the device to delete
     * @throws DeviceNotFoundException if no device exists with that ID
     */
    public void deleteDevice(long id) {
        Device device = getDeviceById(id);  // throws DeviceNotFoundException if not found
        repository.delete(device);
    }

    // -------------------------------------------------------------------------
    // STATUS SUMMARY
    // -------------------------------------------------------------------------

    /**
     * Returns a ranked summary of device counts grouped by status.
     * Delegates the aggregation query to the repository, then maps raw DB rows
     * into typed DeviceStatusSummary objects.
     *
     * CONCEPT — Stream .map() for type transformation:
     * repository.getStatusSummaryWithRank() returns List<Object[]> (raw DB rows).
     * .stream().map(DeviceStatusSummary::new) transforms each Object[] into a
     * DeviceStatusSummary by calling its constructor.
     * DeviceStatusSummary::new is a constructor reference — shorthand for row -> new DeviceStatusSummary(row).
     *
     * @return list of DeviceStatusSummary, ranked by device count descending
     */
    public List<DeviceStatusSummary> getDeviceStatusSummary() {
        return repository.getStatusSummaryWithRank()   // returns raw Object[] rows from SQL
                .stream()
                .map(DeviceStatusSummary::new)          // constructor reference: maps each row to a DTO
                .toList();
    }

    // -------------------------------------------------------------------------
    // ASYNC HEALTH CHECKS
    // -------------------------------------------------------------------------

    /**
     * Simulates a network ping for a single device asynchronously.
     * The HTTP request returns immediately; the ping runs in a background thread.
     * When the ping completes, the device status is updated in the DB.
     *
     * CONCEPT — CompletableFuture:
     * CompletableFuture represents a computation that will complete in the future.
     * It allows non-blocking async operations — the calling thread is freed immediately
     * rather than waiting for the ping to finish (which could take up to 800ms).
     *
     * CONCEPT — supplyAsync():
     * Runs the given lambda on a background thread from the ForkJoinPool (Java's
     * default thread pool). Returns a CompletableFuture<Boolean> that will eventually
     * hold the ping result.
     *
     * CONCEPT — thenApply():
     * Chains a transformation onto the result. When supplyAsync completes (returns
     * isReachable), thenApply runs on the result — updating the device status and
     * saving to DB. This all happens in background threads; the controller returns
     * the CompletableFuture itself to Spring MVC, which handles the async response.
     *
     * CONCEPT — exceptionally():
     * If anything in the chain throws an exception, exceptionally() is called as a
     * fallback — similar to a catch block. Here it sets status to UNKNOWN if the ping
     * fails unexpectedly.
     *
     * @param id the device ID to health-check
     * @return a CompletableFuture that will complete with the updated Device
     */
    public CompletableFuture<Device> checkDeviceHealth(Long id) {
        Device device = getDeviceById(id);  // load synchronously first (throws if not found)

        return CompletableFuture
                .supplyAsync(() -> simulatePing(device))        // run ping in background thread
                .thenApply(isReachable -> {                      // when ping completes, update status
                    device.setStatus(isReachable ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
                    device.setLastSeenAt(Instant.now());
                    return repository.save(device);              // persist updated status
                })
                .exceptionally(ex -> {                           // if ping throws an exception
                    device.setStatus(DeviceStatus.UNKNOWN);      // fall back to UNKNOWN
                    return repository.save(device);
                });
    }

    /**
     * Simulates a network ping by sleeping for a random duration and returning a
     * random boolean (80% chance of returning true = reachable).
     *
     * CONCEPT — ThreadLocalRandom:
     * ThreadLocalRandom is a thread-safe alternative to Math.random() for use in
     * multi-threaded code. Since checkAllDevicesHealth() runs many pings concurrently
     * on different threads, using a shared Random instance would require locking.
     * ThreadLocalRandom gives each thread its own random number generator instance,
     * avoiding contention entirely.
     *
     * CONCEPT — Thread.currentThread().interrupt():
     * Thread.sleep() throws InterruptedException if the thread is interrupted while
     * sleeping (e.g., during JVM shutdown). The correct response is to restore the
     * interrupt flag by calling interrupt() again — this signals to callers higher
     * up the stack that this thread was interrupted, allowing them to handle it.
     * Swallowing the exception (empty catch block) is a common but incorrect pattern.
     *
     * @param device the device to "ping" (used for context but not actually pinged)
     * @return true if the device is "reachable" (80% probability), false otherwise
     */
    private boolean simulatePing(Device device) {
        try {
            // Simulate network latency: sleep between 200ms and 800ms
            Thread.sleep(ThreadLocalRandom.current().nextInt(200, 800));
        } catch (InterruptedException e) {
            // Restore the interrupt flag so callers can detect the interruption
            Thread.currentThread().interrupt();
        }
        // 80% chance online, 20% chance offline — simulates real network conditions
        // nextInt(100) returns 0-99; values 0-79 (80 values) → true
        return ThreadLocalRandom.current().nextInt(100) < 80;
    }

    /**
     * Health-checks ALL devices concurrently, waits for all pings to finish, then
     * returns the updated list. Unlike checkDeviceHealth(), this is synchronous from
     * the caller's perspective — it blocks until every ping completes.
     *
     * CONCEPT — Fan-out / Fan-in Concurrency Pattern:
     * Fan-out: launch N async tasks simultaneously (one ping per device)
     * Fan-in: wait for all N to complete before returning
     *
     * Without concurrency, pinging 7 devices sequentially would take up to 7×800ms = 5.6s.
     * With concurrent pings, it takes as long as the slowest single ping: ~800ms.
     *
     * CONCEPT — CompletableFuture.allOf():
     * Takes an array of CompletableFutures and returns a new future that completes
     * only when ALL of them complete. .join() blocks the current thread until that
     * combined future resolves — effectively a "wait for all" barrier.
     *
     * CONCEPT — .toArray(new CompletableFuture[0]):
     * allOf() requires an array, not a List. Passing new CompletableFuture[0] as the
     * array argument is the idiomatic Java way to convert a List<CompletableFuture<T>>
     * to CompletableFuture<T>[] — the JVM resizes it to the correct length.
     *
     * @return list of all devices with updated statuses after all pings complete
     */
    public List<Device> checkAllDevicesHealth() {
        List<Device> devices = repository.findAll();  // get every device

        // Launch a concurrent ping for each device, collect the futures
        List<CompletableFuture<Device>> futures = devices.stream()
                .map(device -> CompletableFuture
                        .supplyAsync(() -> simulatePing(device))  // run ping on background thread
                        .thenApply(isReachable -> {               // update status when ping resolves
                            device.setStatus(isReachable ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
                            device.setLastSeenAt(Instant.now());
                            return repository.save(device);
                        }))
                .toList();  // all pings are now running concurrently

        // Block until every single ping has finished (fan-in)
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // All futures are guaranteed complete at this point — join() won't block
        return futures.stream()
                .map(CompletableFuture::join)  // extract the result from each completed future
                .toList();
    }
}
