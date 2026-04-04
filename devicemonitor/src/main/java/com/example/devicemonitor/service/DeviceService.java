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

@Service
public class DeviceService {

    private final DeviceRepository repository;
    private final DeviceCacheService cacheService;

    public DeviceService(DeviceRepository repository, DeviceCacheService cacheService) {
        this.repository = repository;
        this.cacheService = cacheService;
    }

    // CREATE & populate Cache
    public Device registerDevice(Device device) {
        if (repository.existsByIpAddress(device.getIpAddress())) {
            throw new IllegalArgumentException("Device " + device.getIpAddress() + " already exists");
        }
        device.setStatus(DeviceStatus.UNKNOWN);
        device.setLastSeenAt(Instant.now());
        Device saved = repository.save(device);
        cacheService.put(saved.getId(), saved);
        return saved;
    }

    // READ ALL - with optional status filter, sort by name -- check cache first
    public List<Device> getAllDevices(Optional<DeviceStatus> status) {
        List<Device> devices = status
                .map(s -> repository.findByStatus(s))
                .orElseGet(() -> repository.findAll());
        return devices.stream()
                .sorted(Comparator.comparing(Device::getName))
                .toList();
    }

    // READ ONE
    public Device getDeviceById(long id) {
        return cacheService.get(id)
                .orElseGet(() -> {
                    Device device = repository.findById(id)
                            .orElseThrow(() -> new DeviceNotFoundException(id));
                    cacheService.put(device.getId(), device);
                    return device;
                });
    }

    // Update
    public Device updateDevice(Long id, Device updates) {
        Device existingDevice = getDeviceById(id);

        Optional.ofNullable(updates.getName())
            .filter(name -> !name.isBlank())
            .ifPresent(existingDevice::setName);

        Optional.ofNullable(updates.getIpAddress())
                .filter(ip -> !ip.isBlank())
                .ifPresent(existingDevice::setIpAddress);

        Optional.ofNullable(updates.getStatus())
                .ifPresent(status -> {
                    existingDevice.setStatus(status);
                    existingDevice.setLastSeenAt(Instant.now());
                });

        Device saved = repository.save(existingDevice);
        cacheService.invalidate(id);
        return saved;
    }

    // Delete
    public void deleteDevice(long id) {
        Device device = getDeviceById(id);
        repository.delete(device);
        cacheService.invalidate(id);
    }

    public List<DeviceStatusSummary> getDeviceStatusSummary() {
        return repository.getStatusSummaryWithRank()
                .stream()
                .map(DeviceStatusSummary::new)
                .toList();
    }

    // Simulates pinging a device asynchronously
    public CompletableFuture<Device> checkDeviceHealth(Long id){
        Device device = getDeviceById(id);

        return CompletableFuture
                .supplyAsync(() -> simulatePing(device))
                .thenApply(isReachable -> {
                    device.setStatus(isReachable ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
                    device.setLastSeenAt(Instant.now());
                    return repository.save(device);
                })
                .exceptionally(ex -> {
                    device.setStatus(DeviceStatus.UNKNOWN);
                    return repository.save(device);
                });
    }

    // Simulates network latency and occassional failures
    private boolean simulatePing(Device device) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(200,800));
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
        // 80% chance online, 20% chance offline -- simulates real network conditions
        return ThreadLocalRandom.current().nextInt(100) < 80;
    }

    public List<Device> checkAllDevicesHealth() {
        List<Device> devices = repository.findAll();

        List<CompletableFuture<Device>> futures = devices.stream()
                .map(device -> CompletableFuture
                        .supplyAsync(() -> simulatePing(device))
                        .thenApply(isReachable -> {
                            device.setStatus(isReachable ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
                            device.setLastSeenAt(Instant.now());
                            return repository.save(device);
                        }))
                .toList();

        // Wait for ALL pings to complete before returning
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }
}
