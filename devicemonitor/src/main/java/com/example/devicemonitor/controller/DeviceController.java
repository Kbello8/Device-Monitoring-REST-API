package com.example.devicemonitor.controller;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.model.DeviceStatusSummary;
import com.example.devicemonitor.service.DeviceCacheService;
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

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final DeviceCacheService cacheService;
    private final DeviceEventPublisher deviceEventPublisher;

    public DeviceController(DeviceService deviceService, DeviceCacheService cacheService, DeviceEventPublisher deviceEventPublisher) {
        this.deviceService = deviceService;
        this.cacheService = cacheService;
        this.deviceEventPublisher = deviceEventPublisher;
    }

    @PostMapping
    public ResponseEntity<Device> registerDevice(
            @Valid @RequestBody Device device) {
                Device created = deviceService.registerDevice(device);

                return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> getDeviceCacheStats() {
        return ResponseEntity.ok(Map.of(
                "cachedDevices", cacheService.size(),
                "ttlSeconds",30
        ));

    }

    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices(
        @RequestParam(required = false) DeviceStatus status){
        List<Device> devices = deviceService.getAllDevices(Optional.ofNullable(status));
        return ResponseEntity.ok(devices);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Device> getDeviceBy(@PathVariable long id){
        return ResponseEntity.ok(deviceService.getDeviceById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Device> getAllDevices(@PathVariable Long id,
                                                      @RequestBody Device updates) {
        return ResponseEntity.ok(deviceService.updateDevice(id, updates));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDevice (@PathVariable Long id){
        deviceService.deleteDevice(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public ResponseEntity<List<DeviceStatusSummary>> getStatusSummary(){
        return ResponseEntity.ok(deviceService.getDeviceStatusSummary());
    }

    @PostMapping("/{id}/health-check")
    public CompletableFuture<ResponseEntity<Device>> checkHealth(@PathVariable long id){
        return deviceService.checkDeviceHealth(id)
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/health-check")
    public ResponseEntity<List<Device>> checkAllHealth(){
        return ResponseEntity.ok(deviceService.checkAllDevicesHealth());
    }

    @GetMapping("/queue/stats")
    public ResponseEntity<Map<String, Object>> getDeviceQueueStats() {
        return ResponseEntity.ok(Map.of(
                "pendingEvents",deviceEventPublisher.size()
        ));
    }


}
