package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * RedisCacheService is the AWS-profile implementation of {@link CacheService},
 * backed by Redis (typically AWS ElastiCache in production).
 *
 * <h2>When is this used?</h2>
 * <p>This bean is activated exclusively when the {@code aws} Spring profile is
 * active ({@code @Profile("aws")}). In all other environments the local
 * {@link DeviceCacheService} implementation is used instead. The profile
 * separation means no Redis infrastructure is required for local development.</p>
 *
 * <h2>Why Redis in the cloud?</h2>
 * <p>When the application is deployed across multiple ECS task instances, each
 * instance would have its own separate in-process cache ({@link DeviceCacheService}),
 * leading to cache inconsistency — one instance might serve stale data while
 * another has already invalidated its local entry. Redis solves this by providing
 * a single shared cache that all instances read from and write to, ensuring
 * consistency across the fleet.</p>
 *
 * <h2>Key strategy</h2>
 * <p>All device cache keys are prefixed with {@code "device:"} (e.g.,
 * {@code "device:42"}) to namespace them within the Redis keyspace. This avoids
 * collisions with other cached data and enables targeted bulk operations like
 * {@link #invalidateAll()} which scans for the {@code "device:*"} pattern.</p>
 *
 * <h2>TTL</h2>
 * <p>Each entry is stored with a 30-second TTL, matching the
 * {@link DeviceCacheService} behaviour so both implementations have consistent
 * freshness guarantees regardless of which profile is active.</p>
 *
 * <h2>Serialisation</h2>
 * <p>{@link ObjectMapper#findAndRegisterModules()} is called at construction to
 * register the Jackson JavaTimeModule, which is needed to correctly serialise
 * and deserialise the {@code Instant} fields on {@link Device} (e.g.,
 * {@code lastSeenAt}). Without this, Instant fields would fail to round-trip
 * through Redis.</p>
 *
 * <h2>Error handling</h2>
 * <p>Every method catches all exceptions and logs at WARN level, then returns a
 * safe fallback ({@code Optional.empty()} for reads, silent no-op for writes).
 * This implements the "cache is not authoritative" principle: if Redis is
 * temporarily unavailable, the application falls back to the database, degrading
 * gracefully rather than returning 500 errors.</p>
 */
@Service
@Profile("aws")
public class RedisCacheService implements CacheService {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);

    /**
     * All device cache keys are prefixed with this string to namespace them in
     * the shared Redis instance and enable pattern-based bulk operations.
     */
    private static final String KEY_PREFIX = "device:";

    /**
     * Cache entry lifetime. Matches the TTL used by {@link DeviceCacheService}
     * to ensure consistent freshness guarantees across both implementations.
     */
    private static final Duration TTL = Duration.ofSeconds(30);

    /**
     * Spring Data Redis template configured to use JSON serialisation. The
     * template's value serialiser must support {@link Device} objects;
     * this is typically set up in a Redis configuration class.
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Jackson mapper with time-module support for round-tripping
     * {@code java.time.Instant} fields (e.g., {@code Device.lastSeenAt}).
     */
    private final ObjectMapper objectMapper;

    /**
     * @param redisTemplate Spring Data Redis template, injected by Spring. Must
     *                      be configured with an appropriate value serialiser
     *                      (e.g., {@code GenericJackson2JsonRedisSerializer}).
     */
    public RedisCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        // findAndRegisterModules discovers and registers all Jackson modules on
        // the classpath, including JavaTimeModule for java.time.* support.
        // This is critical for correctly serialising Device.lastSeenAt (Instant).
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules();
    }

    /**
     * Retrieves a {@link Device} from Redis by its ID.
     *
     * <p>Redis returns the stored value as a generic {@link Object} (the exact
     * runtime type depends on the template's deserialiser). {@link ObjectMapper#convertValue}
     * is then used to safely coerce that object into a typed {@link Device}
     * instance — this handles both the case where Redis returns a
     * {@code LinkedHashMap} (from JSON deserialisation) and a proper
     * {@link Device} instance (if the deserialiser is type-aware).</p>
     *
     * @param id the device primary key; combined with {@link #KEY_PREFIX} to
     *           form the Redis key.
     * @return an {@link Optional} containing the cached device, or
     *         {@link Optional#empty()} on a miss or Redis error.
     */
    public Optional<Device> get(Long id) {
        try {
            // Build the namespaced Redis key and retrieve the stored value.
            Object value = redisTemplate.opsForValue().get(KEY_PREFIX + id);
            if (value == null) return Optional.empty();
            // convertValue handles the Object→Device coercion regardless of
            // what runtime type the Redis deserialiser produced.
            Device device = objectMapper.convertValue(value, Device.class);
            return Optional.of(device);
        } catch (Exception e) {
            // Treat any Redis failure as a cache miss so the caller falls through
            // to the database. Log at WARN (not ERROR) because Redis is a
            // performance optimisation, not a correctness requirement.
            logger.warn("Redis cache read failed for device {}", id, e);
            return Optional.empty();
        }
    }

    /**
     * Stores a {@link Device} in Redis under the namespaced key with the
     * configured TTL.
     *
     * <p>The TTL is set on every write (even on an update) so that the expiry
     * timer is always reset to a full 30 seconds from the last write, rather
     * than counting down from the original insertion time.</p>
     *
     * @param id     the device primary key (used as part of the cache key).
     * @param device the device to cache; serialised to JSON by the template.
     */
    public void put(Long id, Device device) {
        try {
            // opsForValue().set with a Duration atomically sets the value and
            // its TTL in a single Redis command (SET key value EX seconds).
            redisTemplate.opsForValue()
                    .set(KEY_PREFIX + id, device, TTL);
        } catch (Exception e) {
            // A write failure is non-fatal — the device was already persisted to
            // the database. Log at WARN and continue.
            logger.warn("Redis cache write failed for device {}", id, e);
        }
    }

    /**
     * Deletes the Redis key for the given device ID, forcing the next read to
     * hit the database.
     *
     * <p>Called by {@link DeviceService} after a successful update or delete
     * to ensure the cache does not serve stale data within its TTL window.</p>
     *
     * @param id the device primary key whose cache entry should be deleted.
     */
    public void invalidate(Long id) {
        try {
            redisTemplate.delete(KEY_PREFIX + id);
        } catch (Exception e) {
            logger.warn("Redis cache invalidation failed for device {}", id, e);
        }
    }

    /**
     * Deletes all keys in Redis that match the {@code "device:*"} pattern,
     * effectively clearing the entire device cache.
     *
     * <p>Uses {@link RedisTemplate#keys(Object)} which issues a Redis
     * {@code KEYS} command — this is O(N) and may block the Redis server
     * briefly on large keyspaces. For production deployments with millions of
     * keys, this should be replaced with a {@code SCAN}-based approach to
     * avoid blocking other Redis clients.</p>
     */
    public void invalidateAll() {
        try {
            // Fetch all keys in the device namespace, then delete them in bulk.
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            logger.warn("Redis cache clear failed");
        }
    }

    /**
     * Returns the number of keys in Redis matching the {@code "device:*"} pattern.
     *
     * <p>Like {@link #invalidateAll()}, this uses the {@code KEYS} command which
     * is O(N). Acceptable for monitoring/diagnostics but should not be called in
     * a hot path. Returns 0 on any Redis error.</p>
     *
     * @return the number of cached device entries currently in Redis.
     */
    public int size() {
        try {
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            logger.warn("Redis cache size check failed");
            return 0;
        }
    }
}
