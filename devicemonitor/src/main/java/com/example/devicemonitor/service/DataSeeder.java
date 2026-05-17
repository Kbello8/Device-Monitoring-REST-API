package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.repository.DeviceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;

/**
 * DataSeeder populates the in-memory H2 database with a representative set of
 * test devices on application startup.
 *
 * <p>This class implements {@link CommandLineRunner}, which Spring Boot calls
 * automatically after the application context is fully initialized. Using
 * CommandLineRunner (rather than a @PostConstruct method or an ApplicationListener)
 * ensures the JPA/Hibernate schema has already been created before we attempt
 * any inserts, and it runs on every startup cycle.</p>
 *
 * <p>The guard {@code deviceRepository.count() > 0} makes the seeder idempotent:
 * if the database already contains rows (e.g., because another seeder ran first,
 * or in a future scenario where the DB is persistent), seeding is skipped
 * entirely to avoid duplicate data.</p>
 *
 * <p>Because the application uses an H2 in-memory database with create-drop
 * semantics, these records are reset on every restart — they exist purely to
 * allow manual exploration via the H2 console and to give the REST endpoints
 * something to return without requiring a client to POST devices first.</p>
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final DeviceRepository deviceRepository;

    /**
     * Constructor injection is preferred over field injection because it makes
     * the dependency explicit and allows the class to be easily unit-tested
     * without a Spring context.
     *
     * @param deviceRepository JPA repository used to persist the seed devices.
     */
    public DataSeeder(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /**
     * Entry point called by Spring Boot after the application context starts.
     *
     * <p>Seeds 7 devices that cover all three {@link DeviceStatus} values
     * (ONLINE, OFFLINE, UNKNOWN) so that the status-summary endpoint returns
     * meaningful data immediately after startup. The mix of statuses also lets
     * developers exercise the optional status-filter on {@code GET /api/devices}
     * right away.</p>
     *
     * @param args command-line arguments passed to the application (not used here).
     * @throws Exception propagated from {@link DeviceRepository#saveAll} if
     *                   persistence fails; Spring Boot will log and exit.
     */
    @Override
    public void run(String... args) throws Exception {
        // Skip seeding if data already exists — prevents duplicates on hot-reload
        // or if the database is swapped out for a persistent store in the future.
        if (deviceRepository.count() > 0) return;

        // Build a fixed list of representative devices spanning the private
        // 192.168.1.x subnet.  Variety in status ensures every code path in
        // DeviceService and DeviceController can be exercised without extra setup.
        List<Device> devices = List.of(
                makeDevice("Workstation-01", "192.168.1.10", DeviceStatus.ONLINE),
                makeDevice("Workstation-02", "192.168.1.11", DeviceStatus.ONLINE),
                makeDevice("Server-Primary", "192.168.1.20", DeviceStatus.ONLINE),
                makeDevice("Server-Backup",  "192.168.1.21", DeviceStatus.OFFLINE),
                makeDevice("Router-Core",    "192.168.1.1",  DeviceStatus.ONLINE),
                makeDevice("Printer-Floor2", "192.168.1.50", DeviceStatus.OFFLINE),
                makeDevice("Laptop-Finance", "192.168.1.30", DeviceStatus.UNKNOWN)
        );

        // Persist all devices in a single batch call to minimise round-trips.
        deviceRepository.saveAll(devices);
        System.out.println("Seeded " + devices.size() + " devices");
    }

    /**
     * Factory helper that constructs a fully-initialised {@link Device} entity
     * ready for persistence.
     *
     * <p>Bypasses the {@code status = UNKNOWN} default enforced by
     * {@link DeviceService#registerDevice} so the seed data can include devices
     * in every status state. {@code lastSeenAt} is set to the current instant
     * so that the field is never null in the database.</p>
     *
     * @param name      human-readable device name (e.g., "Server-Primary").
     * @param ipAddress IPv4 address string, assumed unique within the seed list.
     * @param status    initial {@link DeviceStatus} to assign to the device.
     * @return a transient {@link Device} instance (not yet persisted).
     */
    private Device makeDevice(String name, String ipAddress, DeviceStatus status) {
        Device device = new Device(name, ipAddress);
        device.setStatus(status);
        // Record the seed time as the initial lastSeenAt so the field is meaningful
        // from the first request rather than being null.
        device.setLastSeenAt(Instant.now());
        return device;
    }
}
