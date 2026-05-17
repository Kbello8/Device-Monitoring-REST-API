# Model Package Summary

**Package:** `com.example.devicemonitor.model`
**Location:** `devicemonitor/src/main/java/com/example/devicemonitor/model/`

---

## Purpose

The `model` package defines every data type in the application — both the persistent JPA entities written to the H2 database and the transient in-memory value objects passed between layers. It is the vocabulary of the system: all other packages (controller, service, repository) speak in terms of these types.

---

## File Descriptions

### `Device.java`

The core JPA entity. Maps to the `devices` database table and represents a single monitored network device.

**Responsibilities:**
- Holds the canonical state of a device: `id`, `name`, `ipAddress`, `status`, and `lastSeenAt`.
- Defines validation constraints (`@NotBlank`) that are enforced by Bean Validation before the entity reaches the repository.
- Provides a convenience constructor that initializes every new device to `status=UNKNOWN` and sets a baseline `lastSeenAt` timestamp.
- Exposes full getter/setter access to support both JPA hydration (no-arg constructor + setters) and service-layer mutation.

**Why no `@UniqueConstraint` on `ipAddress`?** Uniqueness is checked in `DeviceService.registerDevice` so the application can return a meaningful HTTP 409 / `IllegalArgumentException` rather than surfacing a raw SQL constraint violation that would require parsing the exception message.

---

### `DeviceStatus.java`

An enum encoding the three possible reachability states of a device.

**Values:**
| Constant | Meaning |
|----------|---------|
| `ONLINE` | The most recent health-check ping succeeded. |
| `OFFLINE` | The most recent ping failed; device is unreachable. |
| `UNKNOWN` | No health check has been run yet (initial state). |

**Key design choice:** Stored in the database via `@Enumerated(EnumType.STRING)` so the column is human-readable and safe against future reordering of enum constants (ordinal storage would break if constants were reordered or inserted).

`UNKNOWN` exists specifically to distinguish "never checked" from "checked and found offline", which prevents the API from misreporting a brand-new device as `OFFLINE`.

---

### `DeviceStatusSummary.java`

A lightweight DTO (Data Transfer Object) that carries a single row from the status-aggregation native SQL query.

**Responsibilities:**
- Receives a raw `Object[]` from the JDBC result set returned by `DeviceRepository.getDeviceStatusSummary()`.
- Performs safe type coercion: status name is cast directly to `String`; numeric fields are up-cast to `Number` then `.longValue()` to handle the varying numeric sub-types returned by different JDBC drivers (`Integer`, `Long`, `BigDecimal`, etc.).
- Exposes three read-only fields: `status`, `deviceCount`, and `rank`.

**Not a JPA entity.** No `@Entity` annotation — this class is never written to the database. It is purely a read-side projection.

**The `rank` field** is produced by a SQL `RANK() OVER (ORDER BY COUNT(*) DESC)` window function in the repository query, giving rank 1 to the status with the most devices. This ranking is computed in the database, not in application code, making it efficient for any number of status groups.

---

### `OutboxEvent.java`

A JPA entity implementing the **Transactional Outbox** pattern for reliable event delivery.

**Responsibilities:**
- Stores serialized event records in the `outbox_events` table within the same database transaction as the triggering device mutation.
- Tracks delivery lifecycle through the nested `Status` enum (`PENDING` → `PROCESSED` or `FAILED`).
- Records both `createdAt` (insertion time) and `processedAt` (delivery attempt time) for SLA monitoring and debugging.
- Provides two state-transition methods — `markProcessed()` and `markFailed()` — rather than a raw `setStatus()` setter, so that business intent is explicit and `processedAt` is always set consistently with the status.

**Why an outbox?** A device mutation and the downstream notification (to a message broker, audit log, etc.) must not be split across two separate transactions. By writing the event to `outbox_events` in the same transaction as the `devices` row update, the system guarantees that if the device write commits, the event record also commits. A separate relay process then forwards `PENDING` rows to the broker at-least-once.

**Relationship to `DeviceEvent`:** The `payload` field holds a serialized (JSON) snapshot of a `DeviceEvent`. The `eventType` field mirrors `DeviceEvent.EventType` so the relay can route events without deserializing the full payload.

---

### `DeviceEvent.java`

An immutable, in-memory value object representing a business-meaningful state change on a device.

**Responsibilities:**
- Captures a point-in-time snapshot: which device changed (`deviceId`, `deviceName`), what changed (`eventType`), what the resulting status was (`status`), and exactly when it happened (`occuredAt`).
- All fields are `final` — events are historical facts and are never mutated after creation.
- Provides a `toString()` override suitable for log lines.

**Not persisted directly.** `DeviceEvent` has no `@Entity` annotation. The service layer creates a `DeviceEvent`, serializes it to JSON, and stores the JSON string as the `payload` of a persisted `OutboxEvent`.

**`EventType` enum** (nested inside this class):
| Constant | When raised |
|----------|-------------|
| `DEVICE_CREATED` | A new device is registered. |
| `DEVICE_UPDATED` | An existing device's attributes are modified. |
| `DEVICE_DELETED` | A device is removed. |
| `HEALTH_CHECK_COMPLETED` | A ping cycle finishes (status becomes `ONLINE` or `OFFLINE`). |

**Known typo:** The field is named `occuredAt` (one `r`) rather than `occurredAt` (two `r`s). The typo is preserved intentionally to avoid breaking any downstream consumers that may already reference the field name in serialized payloads.

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `@Enumerated(EnumType.STRING)` on all enum fields | Column values remain human-readable in the DB and are immune to enum constant reordering. |
| IP uniqueness enforced in `DeviceService`, not the DB | Allows the application to return a controlled error response rather than a raw SQL exception. |
| `DeviceEvent` is immutable (`final` fields, no setters) | Events represent past facts; mutation would undermine the event log's integrity. |
| `DeviceStatusSummary` uses `Object[]` constructor | Spring Data JPA native queries return `Object[]`; this DTO absorbs the cast logic in one place rather than spreading it to the service. |
| Outbox `processedAt` is set on both success and failure | Enables operators to see when a delivery attempt occurred regardless of outcome, supporting failure diagnosis and SLA measurement. |
| `DeviceEvent` payload is stored as a JSON string in `OutboxEvent` | Decouples the relay process from the JPA model; it can forward the payload without querying or re-joining against the `devices` table. |

---

## Notable Relationships Between Models

```
DeviceStatus  (enum)
      ▲
      │ used by
      │
   Device  (entity, "devices" table)
      │
      │ id referenced by
      ▼
  OutboxEvent  (entity, "outbox_events" table)
      │
      │ payload serialized from
      ▼
  DeviceEvent  (transient value object)
      │
      │ EventType enum shared with
      ▼
  OutboxEvent.eventType

  DeviceStatusSummary  (read-only DTO)
      └── populated by native SQL query in DeviceRepository
          (no direct relationship to Device entity at the Java level)
```

---

## Interactions With Other Packages

| Package | How it uses the model |
|---------|----------------------|
| **`controller`** (`DeviceController`) | Receives and returns `Device` objects as JSON via Spring's message converters. Also returns `List<DeviceStatusSummary>` from the summary endpoint. |
| **`service`** (`DeviceService`) | Creates `Device` instances, updates their `status` and `lastSeenAt`, creates `DeviceEvent` and `OutboxEvent` instances after mutations, and returns `DeviceStatusSummary` lists from the repository. |
| **`repository`** (`DeviceRepository`) | Persists and retrieves `Device` and `OutboxEvent` entities. The status-summary native query produces rows that are manually mapped into `DeviceStatusSummary` instances in the service layer. |
| **`exception`** (`GlobalExceptionHandler`) | Operates on exceptions thrown when model constraints are violated (e.g. `DeviceNotFoundException` when a device ID is not found, `IllegalArgumentException` for duplicate IP). |
