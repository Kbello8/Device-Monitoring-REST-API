# Device Monitoring REST API — Master Code Summary

**Generated from:** per-package agent analysis of all source files under
`devicemonitor/src/main/java/com/example/devicemonitor/`

---

## Table of Contents

1. [Application Overview](#1-application-overview)
2. [Layered Architecture](#2-layered-architecture)
3. [Package Summaries](#3-package-summaries)
   - [config](#31-config)
   - [controller](#32-controller)
   - [exception](#33-exception)
   - [model](#34-model)
   - [repository](#35-repository)
   - [service](#36-service)
4. [Cross-Cutting Design Patterns](#4-cross-cutting-design-patterns)
5. [Bugs and Issues Found](#5-bugs-and-issues-found)
6. [Full Dependency Map](#6-full-dependency-map)
7. [AWS vs Local Profile Differences](#7-aws-vs-local-profile-differences)
8. [Endpoint Reference](#8-endpoint-reference)

---

## 1. Application Overview

The Device Monitoring REST API is a Spring Boot application that tracks the health and status
of networked devices. It exposes a REST API for registering, updating, and deleting devices,
performing async health checks (simulated pings), and querying status summaries.

The application is designed to run in two modes:

- **Local / development** — H2 in-memory database, in-process hash map cache, no AWS dependencies.
- **AWS / production** — Same H2 database (ECS), Redis via ElastiCache for distributed caching,
  AWS SQS for event streaming, AWS Secrets Manager for credentials.

The Spring Profile system (`spring.profiles.active=aws`) switches between these two modes with
no code changes and no recompile.

---

## 2. Layered Architecture

```
HTTP Client
    │
    ▼
┌─────────────────────────────┐
│      DeviceController       │  ← HTTP layer. Validates, delegates, formats responses.
│  (controller package)       │    No business logic. Exceptions handled globally.
└────────────┬────────────────┘
             │
             ▼
┌─────────────────────────────┐
│       DeviceService         │  ← Business logic: IP uniqueness, caching, async health
│   (service package)         │    checks, Transactional Outbox, event publication.
└──────┬─────────┬────────────┘
       │         │
       ▼         ▼
┌──────────┐  ┌────────────────────────┐
│ Device   │  │  OutboxEvent           │  ← Repository layer (Spring Data JPA).
│ Repo     │  │  Repository            │    Auto-generated SQL + one native window query.
└──────────┘  └────────────────────────┘
       │
       ▼
┌─────────────────────────────┐
│   H2 In-Memory Database     │  ← Schema: devices table + outbox_events table.
│   (create-drop on restart)  │
└─────────────────────────────┘

Supporting services (injected by profile):
  CacheService  →  DeviceCacheService (local)  |  RedisCacheService (aws)
  Event flow    →  DeviceEventPublisher → DeviceEventConsumer → SqsEventPublisher (aws)
  Secrets       →  AwsSecretsService  (all profiles — see bug note)
  Config        →  AwsConfig          (aws profile only)
  Seeder        →  DataSeeder         (startup, all profiles)
```

---

## 3. Package Summaries

### 3.1 config

**Package:** `com.example.devicemonitor.config`
**Files:** `AwsConfig.java`

Declares all Spring beans required when the application runs on AWS. The entire class is
`@Profile("aws")` — skipped entirely during local development, so no AWS credentials are
needed to run or test the application locally.

**Beans registered:**
| Bean | Type | Purpose |
|------|------|---------|
| `awsSecrets()` | `Map<String,String>` | Fetches the full `devicemonitor/prod` secret bundle from Secrets Manager once; other beans consume individual values by key. |
| `redisConnectionFactory()` | `LettuceConnectionFactory` | Connects to ElastiCache using hostname from `spring.data.redis.host`. Lettuce is chosen for thread-safe connection sharing and reactive compatibility. |
| `redisTemplate()` | `RedisTemplate<String,Object>` | Application-wide Redis client used by `RedisCacheService`. |
| `sqsClient()` | `SqsClient` | AWS SDK v2 SQS client using the default credential provider chain (IAM role in ECS, env vars locally). |

**Key decisions:** Profile-based activation, constructor injection, centralised secret fetch,
Lettuce over Jedis, standalone Redis topology, SDK default credential chain.

---

### 3.2 controller

**Package:** `com.example.devicemonitor.controller`
**Files:** `DeviceController.java`

The HTTP entry point. Mounted at `/api/devices`. No business logic — translates HTTP requests
into service calls and HTTP responses. All error formatting is delegated to `GlobalExceptionHandler`.

**Endpoints:**

| Verb | Path | Handler | Response |
|------|------|---------|----------|
| POST | `/api/devices` | `registerDevice` | 201 + `Device` |
| GET | `/api/devices` | `getAllDevices` | 200 + `List<Device>` |
| GET | `/api/devices/{id}` | `getDeviceBy` | 200 + `Device` |
| PUT | `/api/devices/{id}` | `getAllDevices` (**misnamed**) | 200 + `Device` |
| DELETE | `/api/devices/{id}` | `deleteDevice` | 204 |
| POST | `/api/devices/{id}/health-check` | `checkHealth` | `CompletableFuture<ResponseEntity<Device>>` |
| POST | `/api/devices/health-check` | `checkAllHealth` | 200 + `List<Device>` |
| GET | `/api/devices/summary` | `getStatusSummary` | 200 + `List<DeviceStatusSummary>` |
| GET | `/api/devices/cache/stats` | `getDeviceCacheStats` | 200 + `{cachedDevices, ttlSeconds}` |
| GET | `/api/devices/queue/stats` | `getDeviceQueueStats` | 200 + `{pendingEvents}` |

**Key decisions:** No try/catch in controller (GlobalExceptionHandler owns that), `@Valid` for
Bean Validation, single-device health check uses Spring async MVC (`CompletableFuture` return),
bulk health check is synchronous at the controller layer but concurrent inside the service,
PUT is implemented as partial update (PATCH semantics), observability endpoints bypass the service.

---

### 3.3 exception

**Package:** `com.example.devicemonitor.exception`
**Files:** `DeviceNotFoundException.java`, `GlobalExceptionHandler.java`

Centralises all error handling. The controller and service layers throw; this package catches
and formats.

**Error response shape (all handlers):**
```json
{ "error": "<message>", "timestamp": "<ISO-8601 UTC instant>" }
```

**Handlers:**
| Handler | Exception | HTTP Status |
|---------|-----------|-------------|
| `handleDeviceNotFoundException` | `DeviceNotFoundException` | 404 |
| `handleBadRequest` | `IllegalArgumentException` | 400 |
| `handleValidation` | `MethodArgumentNotValidException` | 400 |

`DeviceNotFoundException` extends `RuntimeException` (unchecked) so callers never need
`throws` declarations; it bubbles to the web boundary where `@RestControllerAdvice` intercepts it.

**Issues found:** See [Section 5](#5-bugs-and-issues-found).

---

### 3.4 model

**Package:** `com.example.devicemonitor.model`
**Files:** `Device.java`, `DeviceStatus.java`, `DeviceStatusSummary.java`, `OutboxEvent.java`, `DeviceEvent.java`

The vocabulary of the entire system. Every other package speaks in terms of these types.

**Types:**
| Type | Kind | Purpose |
|------|------|---------|
| `Device` | JPA Entity | Core monitored device — `id`, `name`, `ipAddress`, `status`, `lastSeenAt`. |
| `DeviceStatus` | Enum | `ONLINE` / `OFFLINE` / `UNKNOWN`. Stored as `STRING` in the DB. |
| `DeviceStatusSummary` | Read-only DTO | Carries one row from the native SQL window-function query. Not persisted. |
| `OutboxEvent` | JPA Entity | Implements the Transactional Outbox — stores serialized events in the same DB transaction as the triggering mutation. |
| `DeviceEvent` | Transient value object | Immutable point-in-time snapshot of a device state change. Serialized to JSON and stored as `OutboxEvent.payload`. |

**Model relationships:**
```
DeviceStatus (enum)
    ▲ used by
    │
 Device (entity, "devices" table)
    │ id referenced by
    ▼
OutboxEvent (entity, "outbox_events" table)
    │ payload serialized from
    ▼
DeviceEvent (transient value object)
    │ EventType enum shared with
    ▼
OutboxEvent.eventType

DeviceStatusSummary (read-only DTO)
    └── populated by native SQL query, no direct JPA relationship to Device
```

**Key decisions:** `EnumType.STRING` for DB safety, IP uniqueness in the service layer not the
DB, immutable `DeviceEvent`, `Object[]` constructor on `DeviceStatusSummary` to absorb
JDBC driver type variance, `processedAt` set on both success and failure for full auditability.

---

### 3.5 repository

**Package:** `com.example.devicemonitor.repository`
**Files:** `DeviceRepository.java`, `OutboxEventRepository.java`

Spring Data JPA interfaces — Spring auto-generates all SQL and proxy implementations at startup.
The persistence layer is thin by design; business rules live in the service.

**DeviceRepository methods:**
| Method | Purpose |
|--------|---------|
| `findByStatus(DeviceStatus)` | Fetch all devices in a given state. |
| `existsByIpAddress(String)` | Boolean duplicate-IP check before insert. |
| `getStatusSummaryWithRank()` | Native SQL `RANK()` window query — the only native query in the codebase. Returns `List<Object[]>`. |

**The native SQL query:**
```sql
SELECT
    status,
    COUNT(*) AS device_count,
    RANK() OVER (ORDER BY COUNT(*) DESC) AS rank
FROM devices
GROUP BY status
ORDER BY device_count DESC
```
JPQL cannot express `RANK()`, making a native query the only viable option. Each row maps
to index `[0]=status (String)`, `[1]=device_count (Long)`, `[2]=rank (Long)`.

**OutboxEventRepository methods:**
| Method | Purpose |
|--------|---------|
| `findByStatus(OutboxEvent.Status)` | Retrieve all PENDING events for relay processing. |

**Key decisions:** Derived queries over boilerplate DAO code, application-level IP uniqueness
(no DB constraint), native SQL only where JPQL falls short, `List<Object[]>` return (pragmatic
but fragile to column reordering), no pagination on outbox (acceptable while throughput is low).

---

### 3.6 service

**Package:** `com.example.devicemonitor.service`
**Files:** `DeviceService.java`, `CacheService.java`, `DeviceCacheService.java`,
`RedisCacheService.java`, `DeviceEventPublisher.java`, `DeviceEventConsumer.java`,
`SqsEventPublisher.java`, `AwsSecretsService.java`, `DataSeeder.java`

The business-logic layer. Every significant decision lives here.

**File responsibilities:**
| File | Role |
|------|------|
| `DeviceService` | All CRUD, health checks, Transactional Outbox flow, cache-aside reads. |
| `CacheService` | Interface abstracting the cache backend. |
| `DeviceCacheService` | `@Profile("!aws")` — in-process `ConcurrentHashMap` cache with 30 s TTL. |
| `RedisCacheService` | `@Profile("aws")` — Redis-backed distributed cache. Fail-open on errors. |
| `DeviceEventPublisher` | `LinkedBlockingQueue` producer; holds in-memory `DeviceEvent` queue. |
| `DeviceEventConsumer` | Single background thread draining the queue; correlates events with outbox rows. |
| `SqsEventPublisher` | `@Profile("aws")` — serialises events to JSON and sends to SQS. |
| `AwsSecretsService` | Calls AWS Secrets Manager; deserialises JSON secret bundle to `Map<String,String>`. |
| `DataSeeder` | `CommandLineRunner` — inserts 7 seed devices on startup if the table is empty. |

**Key methods in `DeviceService`:**
| Method | Description |
|--------|-------------|
| `registerDevice` | IP uniqueness check → persist device → persist OutboxEvent (same tx) → cache put → publish in-memory event. |
| `getDeviceById` | Cache-aside: cache hit → return; miss → DB fetch → cache put → return. |
| `updateDevice` | Null-safe partial update → persist OutboxEvent → cache invalidate → publish event. |
| `deleteDevice` | Persist OutboxEvent before delete (rollback safety) → DB delete → cache invalidate → publish event. |
| `checkDeviceHealth` | `CompletableFuture.supplyAsync(simulatePing)` — non-blocking ping, updates status+lastSeenAt. |
| `checkAllDevicesHealth` | Fan-out: one future per device → `allOf(...).join()` → collect results. O(max ping) not O(sum). |
| `simulatePing` | 200–800 ms random sleep + 80% ONLINE probability via `ThreadLocalRandom`. |

---

## 4. Cross-Cutting Design Patterns

### Transactional Outbox

All mutating operations (`register`, `update`, `delete`, `healthCheck`) write a corresponding
`OutboxEvent` row in the **same database transaction** as the device change. This guarantees
at-least-once event delivery even if the application crashes between the commit and the
downstream SQS dispatch. A separate background thread (`DeviceEventConsumer`) drains the
in-memory event queue and correlates events with PENDING outbox rows.

Flow:
```
DeviceService (mutate device + write OutboxEvent) — @Transactional
    → commit
DeviceService (post-commit) → publish DeviceEvent to DeviceEventPublisher queue
DeviceEventConsumer (background thread) → drain queue → correlate with OutboxEvent → (TODO) dispatch to SqsEventPublisher
SqsEventPublisher (@Profile("aws")) → send JSON to SQS → mark OutboxEvent PROCESSED
```

### Profile-Based Implementation Selection

| Interface | Local Profile (`!aws`) | AWS Profile (`aws`) |
|-----------|------------------------|---------------------|
| `CacheService` | `DeviceCacheService` (ConcurrentHashMap) | `RedisCacheService` (ElastiCache) |
| Event dispatch | (stubbed in consumer) | `SqsEventPublisher` |
| Infrastructure beans | (none) | `AwsConfig` (Redis, SQS, Secrets) |

### Cache-Aside

`getDeviceById` checks cache → DB on miss → populate cache → return. Write operations
invalidate (not update) the cache entry to avoid stale-write complexity.

### Async Fan-Out with CompletableFuture

`checkAllDevicesHealth` creates one `CompletableFuture` per device (fan-out), then uses
`CompletableFuture.allOf(...).join()` (fan-in). Total latency is bounded by the single
slowest ping (~800 ms) rather than the sum of all pings.

### Constructor Injection Throughout

All `@Service`, `@Controller`, and `@Configuration` classes use constructor injection.
Dependencies are `final`, making each class independently testable without a Spring context.

---

## 5. Bugs and Issues Found

| Severity | Location | Issue | Fix |
|----------|----------|-------|-----|
| **High** | `DeviceCacheService.get()` | Null-check is inverted: `if (cacheEntry != null)` should be `if (cacheEntry == null || cacheEntry.isExpired())`. As written, every cache lookup returns `Optional.empty()`, making the cache a no-op. | Change `!= null` to `== null \|\| cacheEntry.isExpired()`. |
| **Medium** | `GlobalExceptionHandler.handleValidation()` | The per-field violation message string is built into a local `message` variable but `ex.getMessage()` is returned instead. Validation errors surface Spring's raw internal message rather than the readable `[field:violation]` format. | Replace `ex.getMessage()` with `message` in the `Map.of()` call. |
| **Low** | `DeviceController` PUT handler | Method is named `getAllDevices`, colliding in name with the GET handler. Does not affect routing but is confusing and will cause compiler/IDE ambiguity warnings. | Rename to `updateDevice`. |
| **Low** | `GlobalExceptionHandler.java` | An empty `@RestControllerAdvice` class named `DeviceNotFoundExceptionHandler` is appended at the bottom of the file. It registers a Spring bean that does nothing. | Delete the class. |
| **Low** | `DeviceEvent.occuredAt` | Field name has a typo (`occured` instead of `occurred`). Preserved intentionally to avoid breaking downstream JSON consumers that may reference the field by name. | Fix the typo **only** if no downstream consumers exist, and do so in a coordinated deployment. |
| **Low** | `AwsSecretsService` | Missing `@Profile("aws")`. The bean is created in all profiles, meaning local developers must have AWS credentials configured or avoid any code path that calls `getSecrets()`. | Add `@Profile("aws")`. |
| **Low** | `DeviceEventConsumer.processEvent()` | SQS dispatch is stubbed as a `TODO` log statement. Events are consumed from the queue and correlated with outbox rows, but never actually forwarded to `SqsEventPublisher`. | Inject `SqsEventPublisher` and call it when the `aws` profile is active. |
| **Low** | `RedisCacheService.invalidateAll()` / `size()` | Uses Redis `KEYS` command (O(N), blocking). Acceptable for small keyspaces, but will degrade Redis performance under high load. | Replace with a `SCAN`-based approach for production workloads. |
| **Low** | `SqsEventPublisher` | `ObjectMapper` does not call `findAndRegisterModules()`. If `DeviceEvent` gains `java.time` fields, `Instant` serialisation will fail. | Add `objectMapper.findAndRegisterModules()` in the constructor. |

---

## 6. Full Dependency Map

```
DeviceController
  ├── DeviceService
  │     ├── DeviceRepository           (JPA → H2)
  │     ├── OutboxEventRepository      (JPA → H2)
  │     ├── CacheService
  │     │     ├── DeviceCacheService   (@Profile !aws → ConcurrentHashMap)
  │     │     └── RedisCacheService    (@Profile aws  → RedisTemplate → ElastiCache)
  │     └── DeviceEventPublisher
  │           └── DeviceEventConsumer  (background thread)
  │                 ├── OutboxEventRepository
  │                 └── [TODO] SqsEventPublisher  (@Profile aws → SQS)
  ├── CacheService                     (for /cache/stats only)
  └── DeviceEventPublisher             (for /queue/stats only)

GlobalExceptionHandler
  ← catches exceptions from DeviceController, DeviceService, Spring MVC

AwsConfig (@Profile aws)
  ├── AwsSecretsService                (→ AWS Secrets Manager)
  ├── LettuceConnectionFactory         (→ ElastiCache Redis)
  ├── RedisTemplate                    (→ consumed by RedisCacheService)
  └── SqsClient                        (→ consumed by SqsEventPublisher)

DataSeeder
  └── DeviceRepository                 (startup seed only)
```

---

## 7. AWS vs Local Profile Differences

| Concern | Local (`!aws`) | AWS (`aws`) |
|---------|---------------|-------------|
| Cache | `DeviceCacheService` (in-process map, 30 s TTL) | `RedisCacheService` (ElastiCache, shared across instances) |
| Event dispatch | Stubbed TODO log | `SqsEventPublisher` → SQS queue |
| Secrets | Not fetched (no `@Profile` guard — **bug**) | `AwsSecretsService` → Secrets Manager |
| Infrastructure beans | None | `AwsConfig` (Redis, SQS, Secrets) |
| Database | H2 in-memory (always) | H2 in-memory (always — no RDS) |

---

## 8. Endpoint Reference

| Verb | Path | Description | Response |
|------|------|-------------|----------|
| `POST` | `/api/devices` | Register a new device. Body must include `name` and `ipAddress` (`@NotBlank`). IP must be unique. Status forced to `UNKNOWN`. | 201 + `Device` |
| `GET` | `/api/devices` | List all devices, optionally filtered by `?status=ONLINE\|OFFLINE\|UNKNOWN`. Sorted alphabetically by name. | 200 + `List<Device>` |
| `GET` | `/api/devices/{id}` | Get a single device by ID. Cache-aside read. 404 if not found. | 200 + `Device` |
| `PUT` | `/api/devices/{id}` | Partial update (PATCH semantics). Non-null, non-blank fields applied. Cache invalidated. | 200 + `Device` |
| `DELETE` | `/api/devices/{id}` | Delete a device. OutboxEvent written first for rollback safety. | 204 |
| `POST` | `/api/devices/{id}/health-check` | Async ping for one device. Returns immediately; Spring resumes on future completion. Status and `lastSeenAt` updated. | `CompletableFuture<ResponseEntity<Device>>` |
| `POST` | `/api/devices/health-check` | Concurrent ping for all devices. Fan-out + fan-in. Latency = slowest ping (~800 ms max). | 200 + `List<Device>` |
| `GET` | `/api/devices/summary` | Status distribution with `RANK()` window function. Returns count + rank per status group. | 200 + `List<DeviceStatusSummary>` |
| `GET` | `/api/devices/cache/stats` | Observability — number of cached devices and TTL in seconds. | 200 + `{cachedDevices, ttlSeconds}` |
| `GET` | `/api/devices/queue/stats` | Observability — number of pending in-memory events. | 200 + `{pendingEvents}` |
