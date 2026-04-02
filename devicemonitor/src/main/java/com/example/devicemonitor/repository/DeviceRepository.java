package com.example.devicemonitor.repository;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Data access layer for Device entities.
 * This interface is the only place in the application that talks directly to the database.
 *
 * CONCEPT — Repository Pattern:
 * A repository abstracts database operations behind a clean interface. The rest of
 * the application (service layer, controllers) never writes SQL — they call methods
 * on this interface, and the implementation is generated automatically.
 *
 * CONCEPT — Spring Data JPA & JpaRepository:
 * By extending JpaRepository<Device, Long>, this interface inherits a full suite
 * of database operations for free — no implementation class needed:
 *   - save(device)        → INSERT or UPDATE
 *   - findById(id)        → SELECT WHERE id = ?
 *   - findAll()           → SELECT * FROM devices
 *   - delete(device)      → DELETE WHERE id = ?
 *   - count()             → SELECT COUNT(*)
 *   - existsById(id)      → SELECT COUNT(*) > 0
 *
 * The two generic parameters are:
 *   Device → the entity type this repository manages
 *   Long   → the type of the primary key (@Id field in Device)
 *
 * Spring generates a proxy implementation of this interface at startup using reflection.
 * You never write the implementation — Spring writes it for you.
 */
@Repository  // Marks this as a Spring-managed bean; also enables JPA exception translation
public interface DeviceRepository extends JpaRepository<Device, Long> {

    /**
     * Returns all devices that match the given status.
     *
     * CONCEPT — Spring Data Derived Query Methods:
     * Spring Data reads method names and generates SQL from them automatically.
     * "findBy" = SELECT, "Status" = WHERE status = ?
     * So this generates: SELECT * FROM devices WHERE status = ?
     * No @Query annotation needed — the name IS the query definition.
     *
     * @param status the DeviceStatus enum value to filter by
     * @return list of devices with matching status (empty list if none found)
     */
    List<Device> findByStatus(DeviceStatus status);

    /**
     * Checks whether any device already uses the given IP address.
     * Used in DeviceService.registerDevice() to prevent duplicate IPs.
     *
     * CONCEPT — Derived Query with boolean return:
     * "existsBy" generates: SELECT COUNT(*) > 0 FROM devices WHERE ip_address = ?
     * Returns true if at least one matching row exists, false otherwise.
     * More efficient than findByIpAddress() because the DB stops at the first match.
     *
     * @param ipAddress the IP address string to check
     * @return true if a device with that IP already exists
     */
    boolean existsByIpAddress(String ipAddress);

    /**
     * Returns a count and rank of devices grouped by their status.
     * Used by GET /api/devices/summary to show how many devices are in each state.
     *
     * CONCEPT — @Query with Native SQL:
     * Spring Data's derived query names can't express aggregation (GROUP BY, COUNT).
     * @Query lets you write the SQL directly. nativeQuery=true means this is
     * plain SQL (specific to H2/Postgres), not JPQL (JPA's object-oriented query language).
     *
     * CONCEPT — SQL Window Function RANK():
     * RANK() OVER (ORDER BY COUNT(*) DESC) assigns a rank to each status group
     * based on how many devices it has. The group with the most devices gets rank 1.
     * Unlike GROUP BY which just aggregates, window functions compute a value
     * relative to other rows without collapsing them — a powerful analytical SQL feature.
     *
     * Example result:
     *   status   | device_count | rank
     *   ---------+--------------+-----
     *   ONLINE   |      4       |  1
     *   OFFLINE  |      2       |  2
     *   UNKNOWN  |      1       |  3
     *
     * CONCEPT — List<Object[]> return type:
     * Because this is a native query returning non-entity columns (status, count, rank),
     * JPA can't map it to a Device object. It returns raw rows as Object arrays.
     * DeviceStatusSummary's constructor manually maps each Object[] to typed fields.
     */
    @Query(value = """
            SELECT
                status,
                Count(*) AS device_count,
                RANK() OVER (ORDER BY COUNT(*) DESC) as rank
                FROM devices
                GROUP BY status
                ORDER BY device_count DESC
            """, nativeQuery = true)
    List<Object[]> getStatusSummaryWithRank();
}
