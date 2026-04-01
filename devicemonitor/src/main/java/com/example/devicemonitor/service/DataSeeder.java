package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.repository.DeviceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;

@Component
public class DataSeeder implements CommandLineRunner {
    private final DeviceRepository deviceRepository;

    public DataSeeder(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (deviceRepository.count() > 0) return;

        List<Device> devices = List.of(
                makeDevice("Workstation-01", "192.168.1.10", DeviceStatus.ONLINE),
                makeDevice("Workstation-02", "192.168.1.11", DeviceStatus.ONLINE),
                makeDevice("Server-Primary", "192.168.1.20", DeviceStatus.ONLINE),
                makeDevice("Server-Backup",  "192.168.1.21", DeviceStatus.OFFLINE),
                makeDevice("Router-Core",    "192.168.1.1",  DeviceStatus.ONLINE),
                makeDevice("Printer-Floor2", "192.168.1.50", DeviceStatus.OFFLINE),
                makeDevice("Laptop-Finance", "192.168.1.30", DeviceStatus.UNKNOWN)
        );

        deviceRepository.saveAll(devices);
        System.out.println("Seeded " + devices.size() + " devices");
    }

    private Device makeDevice(String name, String ipAddress, DeviceStatus status) {
        Device device = new Device(name,ipAddress);
        device.setStatus(status);
        device.setLastSeenAt(Instant.now());
        return device;
    }
}
