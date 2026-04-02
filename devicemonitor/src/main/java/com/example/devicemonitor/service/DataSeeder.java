package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.repository.DeviceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;

/**
 * Populates the database with sample device data on application startup.
 * Only runs if the database is empty (safe to restart without duplicate data).
 *
 * CONCEPT — CommandLineRunner:
 * CommandLineRunner is a Spring Boot interface with a single method: run().
 * Any Spring bean that implements it will have run() called automatically
 * after the application context is fully initialized — meaning all beans are
 * ready, the database schema is created, and the app is about to start serving requests.
 * It's the standard hook for "do something once at startup."
 *
 * CONCEPT — @Component:
 * @Component marks this class as a Spring-managed bean. Spring detects it during
 * component scanning and registers it in the ApplicationContext. From that point,
 * Spring manages its lifecycle (creation, injection, and in this case, calling run()).
 * @Service and @Repository are specializations of @Component for specific layers;
 * @Component is used here because this class doesn't fit the service or repository roles.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    // Spring injects the DeviceRepository bean via constructor injection (see below)
    private final DeviceRepository deviceRepository;

    /**
     * Constructor injection — Spring sees that this constructor requires a
     * DeviceRepository and automatically passes in the one it created.
     *
     * CONCEPT — Dependency Injection (DI):
     * Instead of this class creating its own DeviceRepository (new DeviceRepository()),
     * Spring creates it and passes it in. This decouples the classes — DataSeeder
     * doesn't need to know how DeviceRepository is constructed. It also makes testing
     * easier: you can pass a mock repository in tests without changing any production code.
     *
     * CONCEPT — Constructor Injection vs Field Injection:
     * Field injection (@Autowired on the field) works but hides dependencies and makes
     * testing harder. Constructor injection makes dependencies explicit and allows
     * the field to be final (immutable after construction), which is safer.
     */
    public DataSeeder(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /**
     * Called once after application startup. Seeds the database if it's empty.
     *
     * CONCEPT — Idempotent Startup Logic:
     * The count() > 0 guard makes this method idempotent — running it multiple times
     * produces the same result as running it once. Without this check, every restart
     * would insert 7 more duplicate devices. This is especially important here because
     * the in-memory H2 database is wiped and recreated on each restart (ddl-auto=create-drop),
     * so count() will always be 0 at startup — but the guard is still good practice.
     *
     * @param args command-line arguments (not used here, but required by the interface)
     */
    @Override
    public void run(String... args) throws Exception {
        // If devices already exist, skip seeding (prevents duplicates on restart)
        if (deviceRepository.count() > 0) return;

        // List.of() creates an immutable list — elements cannot be added/removed after creation
        List<Device> devices = List.of(
                makeDevice("Workstation-01", "192.168.1.10", DeviceStatus.ONLINE),
                makeDevice("Workstation-02", "192.168.1.11", DeviceStatus.ONLINE),
                makeDevice("Server-Primary", "192.168.1.20", DeviceStatus.ONLINE),
                makeDevice("Server-Backup",  "192.168.1.21", DeviceStatus.OFFLINE),
                makeDevice("Router-Core",    "192.168.1.1",  DeviceStatus.ONLINE),
                makeDevice("Printer-Floor2", "192.168.1.50", DeviceStatus.OFFLINE),
                makeDevice("Laptop-Finance", "192.168.1.30", DeviceStatus.UNKNOWN)
        );

        // saveAll() is more efficient than calling save() in a loop — it batches the inserts
        deviceRepository.saveAll(devices);
        System.out.println("Seeded " + devices.size() + " devices");
    }

    /**
     * Helper method to construct a Device with all fields set.
     * Extracted to avoid repeating the same 3-line setup block for every device above.
     *
     * CONCEPT — Private Helper Methods:
     * Small private methods that exist purely to reduce repetition within a class
     * are a basic clean code technique. This method has no business meaning on its own
     * — it just reduces boilerplate in run().
     *
     * @param name      device display name
     * @param ipAddress IPv4 address string
     * @param status    initial DeviceStatus for this seed entry
     * @return a fully constructed Device object (not yet persisted)
     */
    private Device makeDevice(String name, String ipAddress, DeviceStatus status) {
        Device device = new Device(name, ipAddress);  // uses the convenience constructor
        device.setStatus(status);                      // override the default UNKNOWN status
        device.setLastSeenAt(Instant.now());           // set the timestamp to now
        return device;
    }
}
