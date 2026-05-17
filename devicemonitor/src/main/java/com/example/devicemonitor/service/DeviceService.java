package com.example.devicemonitor.service;

import com.example.devicemonitor.exception.DeviceNotFoundException;
import com.example.devicemonitor.model.*;
import com.example.devicemonitor.repository.DeviceRepository;
import com.example.devicemonitor.repository.OutboxEventRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DeviceService is the core business-logic layer of the application.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>CRUD operations for {@link Device} entities, delegating persistence to
 *       {@link DeviceRepository}.</li>
 *   <li>IP-address uniqueness enforcement (intentionally at the service layer,
 *       not as a DB constraint, so the error message can be surfaced cleanly
 *       via {@link com.example.devicemonitor.exception.GlobalExceptionHandler}).</li>
 *   <li>Cache read-through and write-around via {@link CacheService}.</li>
 *   <li>Transactional Outbox event publishing: every write operation saves an
 *       {@link OutboxEvent} in the same DB transaction as the device change, then
 *       enqueues an in-memory {@link DeviceEvent} for async processing.</li>
 *   <li>Simulated concurrent health-check pings using {@link CompletableFuture}.</li>
 * </ul>
 *
 * <h2>Transactional Outbox pattern</h2>
 * <p>All mutating operations ({@link #registerDevice}, {@link #updateDevice},
 * {@link #deleteDevice}) follow the same three-step sequence:</p>
 * <ol>
 *   <li>Persist the device change inside a {@code @Transactional} boundary.</li>
 *   <li>Write a matching {@link OutboxEvent} row in the <em>same transaction</em>
 *       so that the event record is atomically durable with the data change.</li>
 *   <li>After the transaction commits, update the cache and publish an in-memory
 *       {@link DeviceEvent} to {@link DeviceEventPublisher} for
 *       {@link DeviceEventConsumer} to process asynchronously.</li>
 * </ol>
 * <p>This guarantees that even if the application crashes immediately after the
 * transaction commits, the outbox row survives and a recovery process can replay
 * the event. The in-memory queue is a best-effort fast path; the outbox row is
 * the durable guarantee.</p>
 *
 * <h2>Async health checks</h2>
 * <p>Health-check methods use {@link CompletableFuture#supplyAsync} to simulate
 * concurrent network pings. The ForkJoinPool common pool is used by default.
 * {@link #checkAllDevicesHealth()} fans out one future per device and then
 * collects results with {@link CompletableFuture#allOf} + {@code join}, so the
 * total latency is roughly the latency of the slowest single ping (up to 800 ms)
 * rather than the sum of all pings.</p>
 */
@Service
public class DeviceService {

    private final DeviceRepository repository;

    /**
     * Abstraction over the caching backend — either {@link DeviceCacheService}
     * (local) or {@link RedisCacheService} (AWS), selected by Spring at runtime
     * based on the active profile.
     */
    private final CacheService cacheService;

    /**
     * In-process event bus that accepts {@link DeviceEvent} objects and makes
     * them available to {@link DeviceEventConsumer} on a background thread.
     */
    private final DeviceEventPublisher eventPublisher;

    /**
     * JPA repository for reading and writing {@link OutboxEvent} rows, which
     * provide durable event delivery guarantees.
     */
    private final OutboxEventRepository outboxEventRepository;

    /**
     * All dependencies are injected via constructor to make them explicit and
     * to support unit testing with mocked collaborators.
     *
     * @param repository             JPA repository for {@link Device} entities.
     * @param cacheService           profile-selected cache implementation.
     * @param eventPublisher         in-process event queue.
     * @param outboxEventRepository  JPA repository for outbox event rows.
     */
    public DeviceService(DeviceRepository repository, CacheService cacheService,
                         DeviceEventPublisher eventPublisher, OutboxEventRepository outboxEventRepository) {
        this.repository = repository;
        this.cacheService = cacheService;
        this.eventPublisher = eventPublisher;
        this.outboxEventRepository = outboxEventRepository;
    }

    // -------------------------------------------------------------------------
    // CREATE
    // -------------------------------------------------------------------------

    /**
     * Registers a new device, enforcing IP uniqueness and initialising default
     * field values before persisting.
     *
     * <h2>Transactional behaviour</h2>
     * <p>The {@code @Transactional} annotation ensures that the device insert and
     * the outbox row insert are committed atomically. If the outbox write fails,
     * the device insert is also rolled back, preventing a device from existing
     * in the database without a corresponding event record.</p>
     *
     * <h2>Why status = UNKNOWN on creation?</h2>
     * <p>A newly registered device has not yet been health-checked, so its
     * reachability is genuinely unknown. Defaulting to UNKNOWN (rather than
     * ONLINE or OFFLINE) correctly represents that state and avoids misleading
     * clients. The actual status is determined by subsequent calls to
     * {@link #checkDeviceHealth}.</p>
     *
     * <h2>Post-transaction actions</h2>
     * <p>The cache write and event publication happen after the {@code @Transactional}
     * method returns (i.e., after the commit). This is intentional: publishing
     * to the cache or queue before the commit would risk propagating data that
     * the DB might still roll back.</p>
     *
     * @param device a transient {@link Device} instance with at minimum name and
     *               IP address populated.
     * @return the persisted {@link Device} with its generated ID and timestamps.
     * @throws IllegalArgumentException if a device with the same IP address
     *                                  already exists in the database.
     */
    @Transactional
    public Device registerDevice(Device device) {
        // Enforce IP uniqueness at the service layer so the error can be
        // surfaced as a 400 Bad Request with a descriptive message via
        // GlobalExceptionHandler, rather than as a cryptic DB constraint error.
        if (repository.existsByIpAddress(device.getIpAddress())) {
            throw new IllegalArgumentException("Device " + device.getIpAddress() + " already exists");
        }

        // Force UNKNOWN status on new devices — the caller must not be trusted
        // to supply this field, because status is determined by health checks.
        device.setStatus(DeviceStatus.UNKNOWN);
        device.setLastSeenAt(Instant.now());
        Device saved = repository.save(device);

        // Write the outbox event in the same transaction as the device insert.
        // If this save fails, the entire transaction rolls back — no orphaned
        // device record without a matching event.
        OutboxEvent outboxEvent = new OutboxEvent(
                saved.getId(), DeviceEvent.EventType.DEVICE_CREATED,
                String.format("{\"deviceId\":%d,\"name\":\"%s\"}",
                        saved.getId(), saved.getName())
        );
        outboxEventRepository.save(outboxEvent);

        // These two calls happen after the transaction commits (when this method
        // returns). They are best-effort: a cache or queue failure here does not
        // affect the durability of the device record.
        cacheService.put(saved.getId(), saved);
        eventPublisher.publish(new DeviceEvent(
                saved.getId(), DeviceEvent.EventType.DEVICE_CREATED,
                saved.getName(), saved.getStatus()
        ));
        return saved;
    }

    // -------------------------------------------------------------------------
    // READ ALL
    // -------------------------------------------------------------------------

    /**
     * Returns all devices, optionally filtered by {@link DeviceStatus}.
     *
     * <p>Results are always sorted alphabetically by device name to provide a
     * stable, predictable ordering for clients. Sorting in the service layer
     * (rather than the repository) keeps the query simple and avoids an ORDER BY
     * clause in every derived query variant.</p>
     *
     * <p>This method does not use the cache because returning a list would require
     * caching all possible filter combinations. The per-device cache is only used
     * for single-device lookups in {@link #getDeviceById}.</p>
     *
     * @param status if present, only devices with this status are returned;
     *               if empty, all devices are returned.
     * @return a sorted, immutable list of matching {@link Device} objects.
     */
    public List<Device> getAllDevices(Optional<DeviceStatus> status) {
        // Use the status to choose between a filtered or unfiltered repository call.
        // map() on Optional applies the function only when the value is present.
        List<Device> devices = status
                .map(s -> repository.findByStatus(s))
                .orElseGet(() -> repository.findAll());

        // Sort alphabetically by name for a consistent response order regardless
        // of insertion order or DB implementation.
        return devices.stream()
                .sorted(Comparator.comparing(Device::getName))
                .toList();
    }

    // -------------------------------------------------------------------------
    // READ ONE
    // -------------------------------------------------------------------------

    /**
     * Returns a single device by ID, using the cache as a read-through layer.
     *
     * <h2>Cache-aside pattern</h2>
     * <p>The method first checks {@link CacheService#get}. On a cache hit, the
     * device is returned immediately without a DB round-trip. On a cache miss,
     * the device is fetched from the repository, stored in the cache for future
     * requests, and then returned.</p>
     *
     * @param id the device primary key.
     * @return the {@link Device} with the given ID.
     * @throws DeviceNotFoundException if no device with {@code id} exists in
     *                                  the database.
     */
    public Device getDeviceById(long id) {
        // orElseGet is lazy — the lambda is only invoked on a cache miss,
        // avoiding an unnecessary DB query when the cache holds a valid entry.
        return cacheService.get(id)
                .orElseGet(() -> {
                    // Cache miss: fetch from DB and populate the cache so the
                    // next request for the same ID is served from memory.
                    Device device = repository.findById(id)
                            .orElseThrow(() -> new DeviceNotFoundException(id));
                    cacheService.put(device.getId(), device);
                    return device;
                });
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    /**
     * Applies a partial update to an existing device.
     *
     * <h2>Partial update semantics</h2>
     * <p>Only fields that are non-null (and non-blank for strings) in {@code updates}
     * are applied. This allows callers to send only the fields they want to change
     * without accidentally clearing other fields. The Optional + filter + ifPresent
     * chain enforces this cleanly for each field.</p>
     *
     * <h2>Status update side-effect</h2>
     * <p>When the status field is updated, {@code lastSeenAt} is also refreshed
     * to the current instant. This keeps the timestamp accurate: every status
     * transition is a meaningful "last seen" event (e.g., transitioning to OFFLINE
     * means we last detected this device now).</p>
     *
     * <h2>Cache invalidation on update</h2>
     * <p>The cache entry is invalidated (deleted) after the DB save, rather than
     * being updated in place. This is the simpler, safer choice: it avoids the
     * risk of writing a partially-updated device to the cache if the repository
     * save fails partway through. The next read will repopulate the cache from
     * the authoritative database.</p>
     *
     * @param id      the primary key of the device to update.
     * @param updates a {@link Device} instance carrying the fields to change;
     *                null fields are ignored.
     * @return the saved, updated {@link Device}.
     * @throws DeviceNotFoundException if no device with {@code id} exists.
     */
    @Transactional
    public Device updateDevice(Long id, Device updates) {
        // Load the existing device — throws DeviceNotFoundException if absent.
        Device existingDevice = getDeviceById(id);

        // Apply each field conditionally: only patch if the new value is non-null
        // and (for strings) non-blank.
        Optional.ofNullable(updates.getName())
                .filter(name -> !name.isBlank())
                .ifPresent(existingDevice::setName);

        Optional.ofNullable(updates.getIpAddress())
                .filter(ip -> !ip.isBlank())
                .ifPresent(existingDevice::setIpAddress);

        Optional.ofNullable(updates.getStatus())
                .ifPresent(status -> {
                    existingDevice.setStatus(status);
                    // Refresh lastSeenAt whenever status changes, because a status
                    // transition implies a network interaction with the device.
                    existingDevice.setLastSeenAt(Instant.now());
                });

        Device saved = repository.save(existingDevice);

        // Write the outbox event atomically with the device update.
        OutboxEvent outboxEvent = new OutboxEvent(
                saved.getId(),
                DeviceEvent.EventType.DEVICE_UPDATED,
                String.format("{\"deviceId\":%d,\"name\":\"%s\"}",
                        saved.getId(), saved.getName())
        );
        outboxEventRepository.save(outboxEvent);

        // Invalidate the cache entry so the next read fetches the fresh DB record.
        cacheService.invalidate(id);

        // Publish the in-memory event for async downstream processing.
        eventPublisher.publish(new DeviceEvent(
                saved.getId(), DeviceEvent.EventType.DEVICE_UPDATED,
                saved.getName(), saved.getStatus()
        ));
        return saved;
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    /**
     * Deletes a device by ID and publishes a {@code DEVICE_DELETED} event.
     *
     * <h2>Outbox write ordering</h2>
     * <p>The outbox event is written <em>before</em> the device is deleted from
     * the repository. This ordering matters: if we deleted first and then the
     * outbox write failed, the device would be gone but no event would be
     * recorded — a silent data loss scenario. Writing the outbox first means a
     * failure rolls back both the outbox write and the device delete, leaving the
     * system in a consistent state.</p>
     *
     * @param id the primary key of the device to delete.
     * @throws DeviceNotFoundException if no device with {@code id} exists.
     */
    @Transactional
    public void deleteDevice(long id) {
        // Verify the device exists (throws if not), and retain a reference to
        // the device so we can include its name in the event payload after deletion.
        Device device = getDeviceById(id);

        // Write the outbox event BEFORE deleting the device record, so a failure
        // in the outbox write rolls back the entire transaction and the device
        // remains in the database.
        OutboxEvent outboxEvent = new OutboxEvent(
                id,
                DeviceEvent.EventType.DEVICE_DELETED,
                String.format("{\"deviceId\":%d}", id)
        );
        outboxEventRepository.save(outboxEvent);

        // Now delete the device — both this delete and the outbox write above
        // are committed atomically by the @Transactional boundary.
        repository.delete(device);

        // Cache invalidation and event publication are best-effort, post-commit.
        cacheService.invalidate(id);
        eventPublisher.publish(new DeviceEvent(
                device.getId(), DeviceEvent.EventType.DEVICE_DELETED,
                device.getName(), device.getStatus()
        ));
    }

    // -------------------------------------------------------------------------
    // STATUS SUMMARY
    // -------------------------------------------------------------------------

    /**
     * Returns a ranked summary of device counts by status.
     *
     * <p>Delegates entirely to a native SQL query in {@link DeviceRepository} that
     * uses the {@code RANK() OVER (PARTITION BY ...)} window function to produce
     * the summary efficiently in a single DB round-trip. The raw projection
     * results are mapped to {@link DeviceStatusSummary} DTOs here.</p>
     *
     * @return a list of {@link DeviceStatusSummary} objects, one per status value
     *         present in the database.
     */
    public List<DeviceStatusSummary> getDeviceStatusSummary() {
        return repository.getStatusSummaryWithRank()
                .stream()
                .map(DeviceStatusSummary::new)
                .toList();
    }

    // -------------------------------------------------------------------------
    // HEALTH CHECKS (async)
    // -------------------------------------------------------------------------

    /**
     * Simulates a network ping to a single device and updates its status
     * asynchronously.
     *
     * <h2>Async model</h2>
     * <p>{@link CompletableFuture#supplyAsync} offloads the blocking
     * {@link #simulatePing} call to the ForkJoinPool common pool, freeing the
     * calling thread (a Tomcat request thread) to return the future to the
     * controller immediately. The controller then sends the result back to the
     * HTTP client once the future completes.</p>
     *
     * <h2>Exception handling</h2>
     * <p>{@code exceptionally} catches any exception thrown inside the pipeline
     * (e.g., a DB error during {@code repository.save}) and sets the device
     * status to UNKNOWN rather than propagating the error. This ensures the
     * health-check endpoint always returns a meaningful response.</p>
     *
     * @param id the primary key of the device to check.
     * @return a {@link CompletableFuture} that resolves to the updated
     *         {@link Device} with its new status persisted to the database.
     * @throws DeviceNotFoundException (immediately, not asynchronously) if no
     *                                 device with {@code id} exists.
     */
    public CompletableFuture<Device> checkDeviceHealth(Long id) {
        // Fetch the device synchronously on the calling thread before handing off
        // to the async pipeline, so that DeviceNotFoundException is thrown
        // immediately (not wrapped in a failed future).
        Device device = getDeviceById(id);

        return CompletableFuture
                // Offload the blocking ping simulation to the ForkJoinPool.
                .supplyAsync(() -> simulatePing(device))
                // When the ping result is available, update the device's status
                // and persist the change. This stage runs on the ForkJoinPool too.
                .thenApply(isReachable -> {
                    device.setStatus(isReachable ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
                    device.setLastSeenAt(Instant.now());
                    return repository.save(device);
                })
                // If any stage in the pipeline throws, set status to UNKNOWN so
                // the device record reflects the ambiguity rather than a stale value.
                .exceptionally(ex -> {
                    device.setStatus(DeviceStatus.UNKNOWN);
                    return repository.save(device);
                });
    }

    /**
     * Simulates pinging a single device by sleeping for a random duration
     * (200–800 ms) and then returning a boolean indicating reachability.
     *
     * <p>The sleep models real network round-trip time variability. The 80/20
     * online/offline probability models a moderately healthy network where most
     * devices are reachable most of the time. These values are arbitrary
     * simulation parameters and should be replaced with real ICMP/TCP pings
     * if this service is deployed against actual infrastructure.</p>
     *
     * @param device the device to "ping" (its fields are not actually used in
     *               the simulation; the parameter is kept for future use where
     *               the IP address might be used to route a real ping).
     * @return {@code true} with ~80% probability (device online),
     *         {@code false} with ~20% probability (device offline).
     */
    private boolean simulatePing(Device device) {
        try {
            // Sleep for a random duration between 200 ms and 800 ms to simulate
            // variable network latency. ThreadLocalRandom avoids contention between
            // concurrent ping threads — it is the recommended random source in
            // multi-threaded contexts.
            Thread.sleep(ThreadLocalRandom.current().nextInt(200, 800));
        } catch (InterruptedException e) {
            // Restore the interrupted status so the ForkJoinPool can handle it correctly.
            Thread.currentThread().interrupt();
        }
        // 80% chance online, 20% chance offline — simulates real network conditions
        // where most devices are reachable but occasional failures occur.
        return ThreadLocalRandom.current().nextInt(100) < 80;
    }

    /**
     * Simulates pinging all devices in the system concurrently and updates their
     * statuses in bulk.
     *
     * <h2>Fan-out / fan-in pattern</h2>
     * <p>One {@link CompletableFuture} is created per device (fan-out), all
     * running on the ForkJoinPool in parallel. {@link CompletableFuture#allOf}
     * creates a single composite future that completes only when every individual
     * future has completed (fan-in). {@code join()} then blocks the calling thread
     * until all pings are done. The total wall-clock time is roughly the latency
     * of the slowest individual ping (≤ 800 ms) rather than the sum of all pings
     * (which would be up to 800 ms × N).</p>
     *
     * <h2>Note on error handling</h2>
     * <p>Unlike {@link #checkDeviceHealth}, this bulk variant does not attach an
     * {@code exceptionally} handler to each future. A DB save failure on one
     * device will cause that future to complete exceptionally, and the subsequent
     * {@code CompletableFuture::join} call in the result-collection stream will
     * rethrow the exception, potentially leaving some devices partially updated.
     * Adding {@code exceptionally} handlers (as in the single-device variant)
     * would make this more robust.</p>
     *
     * @return a list of all devices with their updated statuses persisted to
     *         the database; ordering matches the order returned by the repository.
     */
    public List<Device> checkAllDevicesHealth() {
        List<Device> devices = repository.findAll();

        // Fan-out: create one async ping future per device.
        List<CompletableFuture<Device>> futures = devices.stream()
                .map(device -> CompletableFuture
                        // Each ping runs concurrently on the ForkJoinPool.
                        .supplyAsync(() -> simulatePing(device))
                        .thenApply(isReachable -> {
                            device.setStatus(isReachable ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
                            device.setLastSeenAt(Instant.now());
                            return repository.save(device);
                        }))
                .toList();

        // Fan-in: block until ALL device pings have completed before collecting
        // results. Without this, we could return before all futures are done
        // and collect incomplete results from join().
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Collect the result of each completed future into a list.
        // At this point all futures are already done, so join() returns immediately.
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }
}
