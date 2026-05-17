# Service Package Summary

## Package Purpose

The `com.example.devicemonitor.service` package is the business-logic layer of the
Device Monitoring REST API. It sits between the `DeviceController` (HTTP layer) and
the `DeviceRepository` / `OutboxEventRepository` (persistence layer). Every significant
decision — IP uniqueness enforcement, event publication, caching strategy, async
health-check simulation — lives here rather than leaking into controllers or
repositories.

The package also contains all infrastructure-facing integrations: in-process event
queuing, AWS SQS publishing, AWS Secrets Manager access, and a pluggable caching
abstraction that switches between an in-process map (local) and Redis (AWS) based on
the active Spring profile.

---

## File Descriptions

### `CacheService.java` — Cache Abstraction Interface

Defines the five-method contract (`get`, `put`, `invalidate`, `invalidateAll`, `size`)
that all cache implementations must satisfy. `DeviceService` depends only on this
interface; the correct implementation is injected by Spring at runtime based on the
active profile. This makes it trivial to swap caching backends without touching any
business logic.

---

### `DeviceCacheService.java` — Local In-Process Cache (`@Profile("!aws")`)

Implements `CacheService` using a `ConcurrentHashMap<Long, CacheEntry>` with a 30-second
TTL. Active in all environments except when the `aws` profile is enabled.

**Key design decisions:**

- **ConcurrentHashMap + ReadWriteLock**: `ConcurrentHashMap` provides thread-safe
  individual operations, but compound operations (the eviction sweep iterates the entire
  map and removes expired entries) are not atomic on their own. A `ReentrantReadWriteLock`
  is layered on top to guard those multi-step operations while allowing concurrent reads.
- **CacheEntry wrapper**: Each cached device is wrapped in a private `CacheEntry` that
  records an absolute `Instant` expiry at insertion time. Lazy expiry is checked in
  `get()`; proactive expiry is handled by the scheduler.
- **Background eviction scheduler**: A `ScheduledExecutorService` runs a `removeIf`
  sweep every 30 seconds to evict all stale entries in one pass, preventing unbounded
  memory growth.
- **Known bug in `get()`**: The null-check condition is inverted (`!= null` instead of
  `== null`), which causes the cache to always return `Optional.empty()` on a hit,
  effectively making it a no-op. The correct check is `if (cacheEntry == null || cacheEntry.isExpired())`.
- **`DeviceRepository` is injected but unused**: It is retained as a hook for a future
  cache-aside auto-load pattern.

---

### `RedisCacheService.java` — Redis-Backed Distributed Cache (`@Profile("aws")`)

Implements `CacheService` using Spring Data Redis's `RedisTemplate<String, Object>`.
Active only when the `aws` profile is enabled, providing a shared cache across
horizontally-scaled ECS instances.

**Key design decisions:**

- **Profile separation**: Without `@Profile("aws")`, multiple `CacheService` beans
  would be present and Spring would fail to auto-wire `DeviceService`. The profile
  ensures exactly one implementation is active at a time.
- **Key namespace**: All keys are prefixed with `"device:"` (e.g., `"device:42"`) to
  avoid collisions with other data in the shared Redis instance and to enable
  pattern-based bulk operations.
- **`findAndRegisterModules()`**: Called on the `ObjectMapper` to register Jackson's
  `JavaTimeModule`, which is required to correctly serialise and deserialise
  `java.time.Instant` fields such as `Device.lastSeenAt`. Without this, Instant
  round-trips through Redis would fail.
- **Fail-open error handling**: Every method catches all exceptions, logs at WARN, and
  returns a safe fallback. A Redis outage degrades to more DB reads but does not bring
  the API down.
- **`KEYS` command risk**: `invalidateAll()` and `size()` use `RedisTemplate.keys()`
  which issues a Redis `KEYS` command — O(N) and blocking on large keyspaces. For
  production deployments with many keys this should be replaced with a `SCAN`-based
  approach.

---

### `DeviceService.java` — Core Business Logic

The central service class; all CRUD operations, health-check logic, and the
Transactional Outbox event flow originate here.

**Key methods:**

| Method | Description |
|---|---|
| `registerDevice(Device)` | Creates a device with `status=UNKNOWN`, persists an `OutboxEvent` atomically, then populates the cache and publishes an in-memory event. |
| `getAllDevices(Optional<DeviceStatus>)` | Returns all devices (optionally filtered by status), sorted alphabetically by name. Does not use the cache (list caching would require per-filter variants). |
| `getDeviceById(long)` | Cache-aside read: returns cached device or fetches from DB on a miss and backfills the cache. |
| `updateDevice(Long, Device)` | Applies a partial update (null-safe field patching), writes an outbox event atomically, invalidates the cache entry, and publishes an in-memory event. |
| `deleteDevice(long)` | Writes the outbox event before deleting the device (ordering matters for rollback safety), then invalidates the cache and publishes an event. |
| `getDeviceStatusSummary()` | Delegates to a native SQL `RANK() OVER (PARTITION BY ...)` query in the repository and maps the projection to DTOs. |
| `checkDeviceHealth(Long)` | Returns a `CompletableFuture<Device>` — offloads `simulatePing` to the ForkJoinPool so the calling request thread is not blocked. |
| `checkAllDevicesHealth()` | Fan-out pattern: creates one `CompletableFuture` per device, waits for all with `CompletableFuture.allOf(...).join()`, then collects results. Total latency is the slowest ping (~800 ms), not the sum. |
| `simulatePing(Device)` | Private helper that sleeps 200–800 ms (simulating network latency) and returns `true` with 80% probability. Uses `ThreadLocalRandom` to avoid contention between concurrent callers. |

**Transactional Outbox pattern** (applied consistently across all mutating methods):

1. Persist the device change (`@Transactional` boundary opens).
2. Write a matching `OutboxEvent` row in the same transaction — both commits or both roll back.
3. After the transaction commits: update the cache and publish an in-memory `DeviceEvent`.

---

### `DeviceEventPublisher.java` — In-Process Event Queue (Producer Side)

A `@Service` that wraps a `LinkedBlockingQueue<DeviceEvent>` and exposes three methods:
`publish(DeviceEvent)`, `poll(long timeoutMs)`, and `size()`.

**Key design decisions:**

- **`LinkedBlockingQueue`**: Chosen for its blocking `poll` semantics, which let the
  consumer thread sleep efficiently while the queue is empty. The queue is unbounded
  (no capacity argument), so `offer()` never drops events under normal conditions.
- **`offer()` vs `put()`**: `offer()` is non-blocking — it returns false immediately
  if the queue were capacity-bounded rather than blocking the calling request thread.
  The dropped-event warning log provides observability without blocking.
- **500 ms poll timeout in the consumer**: Not configured here, but the timeout in
  `DeviceEventConsumer.consumeLoop()` uses this method — the value controls how
  responsive the consumer is to shutdown signals.

---

### `DeviceEventConsumer.java` — In-Process Event Queue (Consumer Side)

Drains `DeviceEvent` objects from `DeviceEventPublisher` on a dedicated single
background thread and correlates each event with a matching PENDING `OutboxEvent`
in the database.

**Key design decisions:**

- **Single background thread** (`Executors.newSingleThreadExecutor()`): Event processing
  is expected to be fast and ordering matters — one thread avoids race conditions that
  could arise if multiple threads processed events concurrently.
- **`volatile boolean running`**: The flag is `volatile` so the write in `stop()` is
  immediately visible to the drain loop on the executor thread without requiring
  synchronisation.
- **Graceful shutdown** (`@PreDestroy`): `stop()` sets the flag, calls `shutdown()`,
  waits up to 5 seconds for the in-flight event to finish, then calls `shutdownNow()`
  if the grace period elapses.
- **Outbox correlation**: All PENDING outbox rows are fetched and filtered in-memory by
  `deviceId` and `eventType`. This is a simple but potentially inefficient approach at
  scale — a targeted query would be preferable in a high-throughput system.
- **Downstream dispatch is stubbed**: The actual dispatch call is logged as a TODO. The
  intent is to inject `SqsEventPublisher` here and call it when the "aws" profile is
  active.
- **Error isolation**: Non-`InterruptedException` exceptions in `consumeLoop()` are
  caught and logged without breaking the loop, so a single bad event cannot crash the
  consumer for all subsequent events.

---

### `SqsEventPublisher.java` — AWS SQS Event Publisher (`@Profile("aws")`)

Serialises `DeviceEvent` objects to JSON and sends them to an AWS SQS queue using the
AWS SDK v2 `SqsClient`.

**Key design decisions:**

- **`@Profile("aws")`**: Only instantiated when the `aws` profile is active, preventing
  SQS SDK calls in local environments where no queue exists.
- **`@Value("${aws.sqs.queue-url}")`**: The queue URL is injected from properties rather
  than hardcoded, making it easy to target different queues per environment.
- **Fire-and-forget error handling**: Exceptions are caught and logged at ERROR, not
  rethrown. SQS failures are non-fatal because durability is guaranteed by the
  Transactional Outbox — the PENDING `OutboxEvent` row can be replayed.
- **Plain `ObjectMapper`**: No `findAndRegisterModules()` is called. If `DeviceEvent`
  gains `java.time` fields, this will need to be updated (unlike `RedisCacheService`
  which already handles this).
- **Standard queue, not FIFO**: No `MessageGroupId` or deduplication ID is set,
  implying the target is a Standard SQS queue. Switching to a FIFO queue for ordered
  delivery would require adding these fields.

---

### `AwsSecretsService.java` — AWS Secrets Manager Client

Retrieves secret values from AWS Secrets Manager and deserialises them from JSON into
`Map<String, String>`.

**Key design decisions:**

- **Fail-fast on error**: Any AWS SDK or deserialisation exception is rethrown as a
  `RuntimeException`. If the application cannot retrieve its secrets, it should not
  start in a misconfigured state.
- **Default credential provider chain**: No credentials are passed to
  `SecretsManagerClient.builder()`, so the SDK resolves credentials via the standard
  chain (IAM task role in ECS, env vars or `~/.aws/` locally).
- **Hardcoded region (`us-east-2`)**: This should be externalised to a configuration
  property to support multi-region deployments.
- **No `@Profile` restriction**: Unlike the SQS and Redis beans, this bean is active in
  all profiles. Local developers must either have AWS credentials configured or avoid
  triggering code paths that call `getSecrets()`. Adding `@Profile("aws")` would be a
  low-risk improvement.
- **`@SuppressWarnings("unchecked")`**: Suppresses the raw-type warning from Jackson's
  untyped `readValue(secretValue, Map.class)`.

---

### `DataSeeder.java` — Startup Data Population

Implements `CommandLineRunner` to insert 7 representative test devices into the H2
in-memory database on startup if the table is empty.

**Key design decisions:**

- **`CommandLineRunner`**: Runs after the full Spring context is initialised (including
  JPA schema creation), ensuring the table exists before inserts are attempted.
- **Idempotent guard** (`deviceRepository.count() > 0`): Prevents duplicate seeding if
  the table already contains data. Safe to leave in place if the database is swapped to
  a persistent store.
- **Bypasses `status = UNKNOWN` default**: The `makeDevice` helper sets status directly,
  unlike `DeviceService.registerDevice()` which always forces `UNKNOWN`. This is
  intentional — seed data needs varied statuses for meaningful demo output.
- **7-device coverage**: The seed set includes devices in all three `DeviceStatus` values
  (ONLINE, OFFLINE, UNKNOWN), ensuring that the status-filter endpoint and the
  status-summary endpoint return non-trivial results immediately after startup.

---

## Key Design Patterns

### Transactional Outbox
All write operations in `DeviceService` follow the Transactional Outbox pattern: the
device change and a corresponding `OutboxEvent` row are written in the same database
transaction, then an in-memory `DeviceEvent` is published after the commit. This
guarantees at-least-once event delivery even if the application crashes between the
commit and the downstream dispatch.

### Profile-Based Implementation Selection
`CacheService` has two implementations (`DeviceCacheService` for local, `RedisCacheService`
for AWS) activated by `@Profile("!aws")` and `@Profile("aws")` respectively. Similarly,
`SqsEventPublisher` is only active under `@Profile("aws")`. This pattern keeps the local
development experience dependency-free while enabling full AWS integration in production.

### Cache-Aside (Lazy Population)
`DeviceService.getDeviceById()` implements cache-aside: check the cache first, fall
through to the database on a miss, populate the cache from the DB result, and return.
Write operations invalidate (not update) the cache entry to avoid the complexity of
keeping stale cached state in sync with the database.

### Async Fan-Out with CompletableFuture
`checkAllDevicesHealth()` demonstrates the fan-out/fan-in pattern: one
`CompletableFuture` per device (fan-out), collected with `allOf(...).join()` (fan-in).
This reduces total latency from O(N × ping_time) to O(max_ping_time) — significant
for large device fleets.

---

## Package Interactions

| This package | Depends on |
|---|---|
| `DeviceService` | `DeviceRepository`, `OutboxEventRepository`, `CacheService`, `DeviceEventPublisher` |
| `DeviceCacheService` / `RedisCacheService` | `DeviceRepository` (injected, unused), `RedisTemplate` |
| `DeviceEventConsumer` | `DeviceEventPublisher`, `OutboxEventRepository` |
| `SqsEventPublisher` | `SqsClient` (AWS SDK), `ObjectMapper` |
| `AwsSecretsService` | `SecretsManagerClient` (AWS SDK), `ObjectMapper` |
| `DataSeeder` | `DeviceRepository` |

| This package | Is used by |
|---|---|
| `DeviceService` | `DeviceController` (HTTP layer) |
| `CacheService` implementations | `DeviceService` |
| `DeviceEventPublisher` | `DeviceService` (producer), `DeviceEventConsumer` (consumer) |
| `SqsEventPublisher` | Intended to be called from `DeviceEventConsumer` (currently stubbed) |
| `AwsSecretsService` | Any future service that needs credentials from Secrets Manager |
| `DataSeeder` | Spring Boot startup lifecycle only |
