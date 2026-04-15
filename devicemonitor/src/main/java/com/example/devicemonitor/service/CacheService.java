package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import java.util.Optional;

public interface CacheService {
    Optional<Device> get(Long id);
    void put(Long id, Device device);
    void invalidate(Long id);
    void invalidateAll();
    int size();
}
