package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.repository.DeviceRepository;
import org.hibernate.cache.spi.entry.CacheEntry;
import org.springframework.context.annotation.Profile;
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

/**
 * DeviceCacheService is the local (non-AWS) implementation of {@link CacheService}.
 *
 * <h2>When is this used?</h2>
 * <p>This bean is activated when the {@code aws} Spring profile is <em>not</em>
 * active ({@code @Profile("!aws")}). In practice that means local development
 * and CI. When running on AWS, {@link RedisCacheService} is used instead, which
 * provides a distributed cache shared across all application instances.</p>
 *
 * <h2>Data structure</h2>
 * <p>Cache entries are stored in a {@link ConcurrentHashMap} whose keys are
 * device IDs (Long) and whose values are {@link CacheEntry} wrappers that
 * pair the cached {@link Device} with an expiry timestamp.</p>
 *
 * <h2>Concurrency strategy — why both ConcurrentHashMap AND ReadWriteLock?</h2>
 * <p>{@link ConcurrentHashMap} provides thread-safety for <em>individual</em>
 * operations (get, put, remove). However, compound operations — specifically the
 * eviction sweep in {@link #startEvictionScheduler()} (which iterates the entire
 * map and conditionally removes entries) — are not atomic with respect to
 * concurrent reads and writes. A {@link ReentrantReadWriteLock} is layered on
 * top to serialise these multi-step operations:</p>
 * <ul>
 *   <li>Read lock: held during {@link #get} and {@link #size} so that a
 *       concurrent eviction sweep cannot remove an entry mid-read.</li>
 *   <li>Write lock: held during {@link #put}, {@link #invalidate},
 *       {@link #invalidateAll}, and the eviction sweep to ensure exclusive
 *       access during mutations.</li>
 * </ul>
 *
 * <h2>TTL eviction</h2>
 * <p>Entries are given a wall-clock expiry ({@code Instant.now() + TTL_SECONDS})
 * at insertion time. A {@link ScheduledExecutorService} runs a sweep every
 * {@code TTL_SECONDS} seconds that removes all expired entries in one pass.
 * Expiry is also checked lazily on {@link #get} — an expired entry returns
 * {@code Optional.empty()} even if the scheduler has not swept yet.</p>
 *
 * <p>Note: {@link DeviceRepository} is injected but not currently used by any
 * public method in this class. It is retained as a hook for a future
 * "cache-aside with auto-load" pattern where a cache miss would trigger a DB
 * fetch internally rather than delegating that to the caller.</p>
 */
@Service
@Profile("!aws")
public class DeviceCacheService implements CacheService {

    private final DeviceRepository deviceRepository;

    /**
     * The primary cache storage. {@link ConcurrentHashMap} is chosen for its
     * O(1) average-case get/put and its lock-striping approach (which avoids
     * contention on the full map for individual operations). The ReadWriteLock
     * guards multi-step compound operations that ConcurrentHashMap alone cannot
     * make atomic.
     */
    // The cache itself - thread-safe for individual operations
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Guards compound, multi-step cache operations (iteration + remove) that
     * must be atomic with respect to concurrent reads and writes.
     * A ReadWriteLock is preferred over a simple {@code synchronized} block
     * because multiple readers can proceed concurrently — only writers (including
     * the eviction sweep) require exclusive access.
     */
    // Controls coordinated multi-step operations safely
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * How long a cache entry is considered fresh after insertion.
     * 30 seconds balances read performance (fewer DB round-trips) against
     * staleness risk (at most 30 s of stale device status in the cache).
     */
    // TTL - how long a cache entry is valid before considered stale
    private static final long TTL_SECONDS = 30;

    /**
     * Runs the periodic TTL eviction sweep. A single thread is sufficient
     * because eviction is a lightweight in-memory operation.
     */
    // Background thread that evicts stale entries on a schedule
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * @param deviceRepository injected for potential future use (cache-aside
     *                         auto-load); starts the eviction scheduler on
     *                         construction.
     */
    public DeviceCacheService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
        // Start the eviction scheduler immediately so stale entries do not
        // accumulate from the moment the first device is cached.
        startEvictionScheduler();
    }

    // -------------------------------------------------------------------------
    // Public API (implements CacheService)
    // -------------------------------------------------------------------------

    /**
     * Returns the cached {@link Device} for {@code id} if the entry exists and
     * has not expired.
     *
     * <p>The read lock is acquired to prevent the eviction scheduler from
     * removing the entry in the middle of the null-check and expiry-check
     * sequence. Without the lock, the entry could pass the null check and then
     * be removed by the scheduler before {@code isExpired()} is called.</p>
     *
     * <p><strong>Note — known bug:</strong> The null-check condition is currently
     * inverted: {@code if (cacheEntry != null || cacheEntry.isExpired())} should
     * be {@code if (cacheEntry == null || cacheEntry.isExpired())}. The current
     * code returns {@code Optional.empty()} when the entry exists (non-null) OR
     * is expired, effectively making the cache a no-op. This should be fixed to:
     * {@code if (cacheEntry == null || cacheEntry.isExpired())}.</p>
     *
     * @param id the device primary key.
     * @return {@link Optional} containing the device if cached and fresh;
     *         {@link Optional#empty()} otherwise.
     */
    public Optional<Device> get(Long id) {
        lock.readLock().lock();
        try {
            CacheEntry cacheEntry = cache.get(id);
            // BUG: condition should be `== null` not `!= null` — the current
            // logic returns empty whenever the entry IS present, negating the cache.
            if (cacheEntry != null || cacheEntry.isExpired()) {
                return Optional.empty();
            }
            return Optional.of(cacheEntry.device);
        } finally {
            // Always release the lock in a finally block to prevent deadlocks if
            // an exception is thrown inside the try block.
            lock.readLock().unlock();
        }
    }

    /**
     * Inserts or replaces the cache entry for {@code id}.
     *
     * <p>The write lock ensures that a concurrent eviction sweep cannot observe
     * a partially-constructed {@link CacheEntry}. The lock also prevents the
     * scheduler from removing an entry that was just added (edge case when the
     * sweep runs at the same instant as a {@code put}).</p>
     *
     * @param id     the device primary key (cache key).
     * @param device the device to cache; must not be {@code null}.
     */
    public void put(Long id, Device device) {
        lock.writeLock().lock();
        try {
            // Wrap the device in a CacheEntry to record its expiry timestamp.
            cache.put(id, new CacheEntry(device));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Evicts the cache entry for {@code id}, forcing the next read to go to
     * the database.
     *
     * <p>Called by {@link DeviceService} after a successful update or delete.
     * Using {@code remove} (rather than replacing with an expired entry) makes
     * the eviction immediate and frees memory.</p>
     *
     * @param id the device primary key to evict.
     */
    public void invalidate(Long id) {
        lock.writeLock().lock();
        try {
            cache.remove(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clears all entries from the cache.
     *
     * <p>Useful for administrative operations or test teardown. Unlike the
     * scheduled eviction which only removes <em>expired</em> entries, this
     * method removes all entries regardless of their expiry time.</p>
     */
    public void invalidateAll() {
        lock.writeLock().lock();
        try {
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Eviction
    // -------------------------------------------------------------------------

    /**
     * Schedules a periodic background sweep that removes expired cache entries.
     *
     * <p>The sweep runs every {@code TTL_SECONDS} seconds (with an initial delay
     * of {@code TTL_SECONDS} seconds so the first sweep does not run before any
     * entries are inserted). {@link Map#entrySet() removeIf} is used for a clean,
     * single-pass removal of all entries whose {@link CacheEntry#isExpired()}
     * returns {@code true}.</p>
     *
     * <p>The write lock is held for the entire sweep to prevent a reader from
     * seeing a partially-swept cache.</p>
     */
    private void startEvictionScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            lock.writeLock().lock();
            try {
                // Remove every entry whose expiry has passed in a single atomic sweep.
                cache.entrySet().removeIf(e -> e.getValue().isExpired());
            } finally {
                lock.writeLock().unlock();
            }
        }, TTL_SECONDS, TTL_SECONDS, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Cache Entry Wrapper
    // -------------------------------------------------------------------------

    /**
     * Internal wrapper that pairs a {@link Device} with an absolute expiry
     * timestamp computed at insertion time.
     *
     * <p>Storing the expiry as an {@link Instant} (rather than a duration or a
     * remaining-seconds counter) avoids drift caused by the time between insertion
     * and the first expiry check.</p>
     */
    private static class CacheEntry {

        /** The cached device object. */
        final Device device;

        /** The wall-clock time after which this entry is considered stale. */
        final Instant expiry;

        /**
         * @param device the device to wrap; expiry is set to now + TTL_SECONDS.
         */
        CacheEntry(Device device) {
            this.device = device;
            // Compute expiry at insertion time so the TTL is fixed and unaffected
            // by how long the entry sits in the cache before being read.
            this.expiry = Instant.now().plusSeconds(TTL_SECONDS);
        }

        /**
         * Returns {@code true} if the current wall-clock time is past the
         * entry's expiry timestamp.
         *
         * @return {@code true} if the entry should no longer be served.
         */
        boolean isExpired() {
            return Instant.now().isAfter(expiry);
        }
    }

    // -------------------------------------------------------------------------
    // Monitoring / diagnostics
    // -------------------------------------------------------------------------

    /**
     * Returns the current number of entries in the cache (including entries that
     * may have expired but not yet been swept).
     *
     * <p>The read lock ensures a consistent count is returned even if the
     * eviction scheduler is concurrently modifying the map.</p>
     *
     * @return instantaneous entry count.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
