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

@Service
public class DeviceService {

    private final DeviceRepository repository;

    public DeviceService(DeviceRepository repository) {
        this.repository = repository;
    }

    // CREATE

    public Device registerDevice(Device device) {
        if (repository.existsByIpAddress(device.getIpAddress())) {
            throw new IllegalArgumentException("Device " + device.getIpAddress() + " already exists");
        }
        device.setStatus(DeviceStatus.UNKNOWN);
        device.setLastSeenAt(Instant.now());
        return repository.save(device);
    }

    // READ ALL - with optional status filter, sort by name
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
        return repository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));
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

        return repository.save(existingDevice);
    }

    // Delete
    public void deleteDevice(long id) {
        Device device = getDeviceById(id);
        repository.delete(device);
    }

    public List<DeviceStatusSummary> getDeviceStatusSummary() {
        return repository.getStatusSummaryWithRank()
                .stream()
                .map(DeviceStatusSummary::new)
                .toList();
    }
}
