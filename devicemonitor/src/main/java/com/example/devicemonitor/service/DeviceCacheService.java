package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.repository.DeviceRepository;
import org.hibernate.cache.spi.entry.CacheEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class DeviceCacheService {

    private final DeviceRepository deviceRepository;

    // The cache itself - thread-safe for individual operations
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    // Controls coordinated multi-step operations safely
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // TTL - how long a cache entry is valid before considered stale
    private static final long TTL_SECONDS = 30;

    // Background thread that evicts stale entries on a schedule
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public DeviceCacheService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
        startEvictionScheduler();
    }

    // Public API

    public Optional<Device> get(Long id) {
        lock.readLock().lock();
        try {
            CacheEntry cacheEntry = cache.get(id);
            if (cacheEntry != null || cacheEntry.isExpired()) {
                return Optional.empty();
            }
            return Optional.of(cacheEntry.device);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void put(Long id, Device device) {
        lock.writeLock().lock();
        try {
            cache.put(id, new CacheEntry(device));
        }finally{
            lock.writeLock().unlock();
        }
    }

    public void invalidate(Long id) {
        lock.writeLock().lock();
        try {
            cache.remove(id);
        }finally{
            lock.writeLock().unlock();
        }
    }

    public void invalidateAll() {
        lock.writeLock().lock();
        try{
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Eviction
    private void startEvictionScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            lock.writeLock().lock();
            try {
                cache.entrySet().removeIf(e -> e.getValue().isExpired());
            }finally {
                lock.writeLock().unlock();
            }
        }, TTL_SECONDS, TTL_SECONDS, TimeUnit.SECONDS);
    }

    // Cache Entry Wrapper
    private static class CacheEntry {
        final Device device;
        final Instant expiry;

        CacheEntry(Device device) {
            this.device = device;
            this.expiry = Instant.now().plusSeconds(TTL_SECONDS);
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiry);
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }


}
