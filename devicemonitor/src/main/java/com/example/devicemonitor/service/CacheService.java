package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import java.util.Optional;

/**
 * CacheService defines the contract for all device caching implementations
 * in the application.
 *
 * <h2>Purpose</h2>
 * <p>Rather than coupling {@link DeviceService} directly to a specific cache
 * technology, this interface acts as an abstraction layer. Spring injects the
 * correct implementation at runtime based on the active profile:</p>
 * <ul>
 *   <li><strong>Local / default</strong> — {@link DeviceCacheService}: an
 *       in-process {@link java.util.concurrent.ConcurrentHashMap} with TTL
 *       eviction. No external dependencies required.</li>
 *   <li><strong>"aws" profile</strong> — {@link RedisCacheService}: backed by
 *       AWS ElastiCache (Redis). Enables cache sharing across multiple
 *       horizontally scaled application instances.</li>
 * </ul>
 *
 * <h2>Design decision</h2>
 * <p>Keeping the interface minimal (five methods) means both implementations
 * stay symmetric and it is straightforward to add a third implementation (e.g.,
 * Memcached) without touching {@link DeviceService} at all.</p>
 *
 * <p>All methods are expected to be non-throwing: cache failures should be
 * treated as cache misses and logged at WARN level rather than propagated as
 * exceptions, so that a cache outage degrades performance (more DB reads) but
 * does not take the API offline.</p>
 */
public interface CacheService {

    /**
     * Returns the cached {@link Device} for the given ID, if present and not
     * expired.
     *
     * @param id the device primary key.
     * @return an {@link Optional} containing the cached device, or
     *         {@link Optional#empty()} on a cache miss or read error.
     */
    Optional<Device> get(Long id);

    /**
     * Inserts or replaces the cached entry for the given device ID.
     *
     * <p>Implementations should set an expiry (TTL) on the entry so that
     * stale data is eventually evicted even if {@link #invalidate} is not
     * called explicitly.</p>
     *
     * @param id     the device primary key used as the cache key.
     * @param device the device object to cache; must not be {@code null}.
     */
    void put(Long id, Device device);

    /**
     * Removes the cache entry for the specified device ID.
     *
     * <p>Called by {@link DeviceService} after a successful update or delete
     * so that the next read fetches fresh data from the database.</p>
     *
     * @param id the device primary key whose cache entry should be evicted.
     */
    void invalidate(Long id);

    /**
     * Removes all device entries from the cache.
     *
     * <p>Useful for administrative operations or test teardown. Implementations
     * should scope the clear operation to device keys only (using the
     * {@code device:} prefix) to avoid accidentally evicting unrelated cached
     * data.</p>
     */
    void invalidateAll();

    /**
     * Returns the current number of entries held in the cache.
     *
     * <p>Primarily intended for monitoring, health checks, and unit tests that
     * need to assert cache state.</p>
     *
     * @return the instantaneous entry count; may be approximate in distributed
     *         implementations.
     */
    int size();
}
