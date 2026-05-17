# Repository Package Summary

## Package Purpose

The `repository` package contains the data-access layer for the Device Monitoring REST API. It sits between the service layer and the database, providing all read and write operations against the H2 in-memory database. Both interfaces in this package extend Spring Data JPA's `JpaRepository`, which means Spring auto-generates all SQL and proxy implementations at startup — no boilerplate DAO code is required.

---

## Files

### `DeviceRepository.java`

**Responsibilities:** All CRUD and query operations against the `devices` table.

| Method | Generated SQL (approx.) | Purpose |
|---|---|---|
| `findByStatus(DeviceStatus)` | `SELECT * FROM devices WHERE status = ?` | Fetch all devices in a given state (ONLINE/OFFLINE/UNKNOWN) |
| `existsByIpAddress(String)` | `SELECT COUNT(*) > 0 FROM devices WHERE ip_address = ?` | Lightweight duplicate-IP check before saving a new device |
| `getStatusSummaryWithRank()` | See below | Grouped status count with window-function ranking |

`JpaRepository<Device, Long>` also provides: `save`, `findById`, `findAll`, `deleteById`, `count`, and several others at no cost.

#### Notable Query: `getStatusSummaryWithRank()`

This is the only native SQL query in the codebase. It uses the `RANK()` window function, which is not expressible in JPQL (Spring Data's default query language), making a native query the only viable approach.

```sql
SELECT
    status,
    COUNT(*) AS device_count,
    RANK() OVER (ORDER BY COUNT(*) DESC) AS rank
FROM devices
GROUP BY status
ORDER BY device_count DESC
```

**What it returns:** One row per distinct device status, containing:
- `status` — the status string (`ONLINE`, `OFFLINE`, or `UNKNOWN`)
- `device_count` — how many devices are in that status
- `rank` — relative rank by frequency (rank 1 = most common); ties share the same rank, and the next rank skips accordingly (standard SQL `RANK()` behavior, not `ROW_NUMBER()` or `DENSE_RANK()`)

**Return type:** `List<Object[]>` — each array element maps to one column in the above order. The caller must cast each element appropriately (`String`, `Long`, `Long`).

**Why use `RANK()` instead of just `COUNT`?** The window function adds relative-ordering context (which status dominates the fleet) to each row in a single pass, without requiring a second query or post-processing in Java. This is useful for dashboard-style summaries where the caller wants both the count and the comparative standing of each status group.

---

### `OutboxEventRepository.java`

**Responsibilities:** All CRUD and query operations against the outbox events table, supporting the Transactional Outbox pattern.

| Method | Generated SQL (approx.) | Purpose |
|---|---|---|
| `findByStatus(OutboxEvent.Status)` | `SELECT * FROM outbox_events WHERE status = ?` | Retrieve all events in a given state (e.g., PENDING) for relay processing |

`JpaRepository<OutboxEvent, Long>` also provides: `save`, `findById`, `findAll`, `deleteById`, `count`, and others.

#### The Transactional Outbox Pattern

The outbox pattern solves the "dual-write problem": when a service must both commit a domain change to the database AND emit an event to an external system (message broker, webhook, etc.), doing both atomically with a standard distributed transaction is complex and often impractical.

The solution used here:
1. The event is written to the `outbox_events` table **in the same database transaction** as the domain change, guaranteeing the two writes succeed or fail together.
2. A separate poller or scheduler reads `PENDING` events via `findByStatus(PENDING)`, forwards them to the external system, then marks them `SENT` (or a failure status) via `save()`.
3. If the relay process crashes before marking an event sent, the event remains `PENDING` and will be retried on the next poll cycle — ensuring at-least-once delivery.

`findByStatus` is the key method enabling step 2: it is the query a relay/poller component uses to discover work.

---

## Key Design Decisions

**Spring Data JPA derived queries.** Both repositories rely heavily on Spring Data's method-name convention (`findByStatus`, `existsByIpAddress`). This eliminates boilerplate query code and keeps the interfaces concise. The tradeoff is that query logic is implicit in the method name rather than explicit, so names must be chosen carefully to match the column they target.

**Application-level IP uniqueness enforcement.** There is no `UNIQUE` database constraint on the `ip_address` column. Instead, `DeviceRepository.existsByIpAddress` is called by `DeviceService.registerDevice` before every insert, and an `IllegalArgumentException` is thrown if the IP is already taken. This makes constraint-violation handling entirely predictable in Java — no risk of a raw `DataIntegrityViolationException` surfacing to the caller — but means there is a theoretically possible race condition under concurrent inserts. For this project's single-instance, in-memory scope that is an acceptable tradeoff.

**Native SQL only where necessary.** The `RANK()` window function in `DeviceRepository.getStatusSummaryWithRank()` is the sole native query. Everything else uses derived query generation. This minimizes database-dialect coupling while still enabling advanced SQL features where JPQL falls short.

**`List<Object[]>` for the status summary.** The `getStatusSummaryWithRank` return type is a raw `Object[]` list rather than a typed projection or DTO. This is pragmatic for a single query with a narrow use case, but it transfers the burden of type-casting to the caller and is fragile if column order changes. A `@SqlResultSetMapping` or an interface-based projection would be a more robust long-term approach.

**No pagination on `OutboxEventRepository.findByStatus`.** The method returns a plain `List`, which loads all matching rows into memory. This is acceptable because the outbox is designed to stay small — events are consumed quickly. If throughput requirements grow, switching to `findByStatus(Status, Pageable)` would allow batch-processing events in bounded chunks.

---

## Interactions with Other Packages

| This repository | Calls / Used by |
|---|---|
| `DeviceRepository` | `DeviceService` — for all device persistence: register, update, delete, list, health checks, and status summary |
| `OutboxEventRepository` | Any outbox relay component (scheduler, event publisher) that polls for `PENDING` events and marks them `SENT` after forwarding |

Both repositories are injected via Spring's dependency injection (constructor injection in the service layer) and are available as beans due to the `@Repository` annotation and component scanning.
