package com.example.devicemonitor.repository;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link Device} entities.
 *
 * <p>Extends {@link JpaRepository}, which provides a full suite of standard CRUD
 * operations (save, findById, findAll, delete, count, etc.) out of the box without
 * requiring any implementation code. Spring generates a proxy implementation at
 * application startup based on this interface definition.</p>
 *
 * <p>The generic parameters {@code <Device, Long>} tell Spring Data which entity
 * this repository manages ({@code Device}) and what type its primary key is
 * ({@code Long}, matching the {@code @Id} field on the entity).</p>
 *
 * <p>The {@code @Repository} annotation marks this interface as a Spring-managed
 * bean and enables Spring's unified exception translation — any persistence
 * exceptions (e.g., constraint violations) will be wrapped in Spring's
 * {@code DataAccessException} hierarchy rather than leaking raw JPA exceptions
 * to the service layer.</p>
 *
 * <p>Interacts with: {@code DeviceService}, which calls every method defined here
 * to perform device lookups, existence checks, and status aggregation.</p>
 */
@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    /**
     * Retrieves all devices whose {@code status} field matches the given value.
     *
     * <p>Spring Data derives the SQL automatically from the method name:
     * {@code findBy} signals a SELECT query, and {@code Status} maps to the
     * {@code status} column on the {@code devices} table. The generated query is
     * effectively {@code SELECT * FROM devices WHERE status = ?}.</p>
     *
     * <p>This is used by {@code DeviceService} to filter devices by a specific
     * {@link DeviceStatus} (ONLINE, OFFLINE, or UNKNOWN) when a caller requests
     * only devices in a particular state.</p>
     *
     * @param status the {@link DeviceStatus} enum value to filter by
     * @return a (possibly empty) list of devices with the given status
     */
    List<Device> findByStatus(DeviceStatus status);

    /**
     * Checks whether a device with the given IP address already exists in the database.
     *
     * <p>Spring Data derives a {@code SELECT COUNT(*) > 0} query from the method name:
     * {@code existsBy} produces an existence check, and {@code IpAddress} maps to the
     * {@code ip_address} column. This avoids fetching the full entity just to check
     * for a duplicate — the database returns a single boolean, keeping the query
     * lightweight.</p>
     *
     * <p>IP uniqueness is enforced at the application layer rather than via a
     * database constraint (there is no {@code UNIQUE} column constraint on
     * {@code ip_address}). {@code DeviceService.registerDevice} calls this method
     * before saving a new device and throws {@link IllegalArgumentException} if the
     * IP is already taken. This approach keeps constraint-violation handling in Java
     * rather than requiring the caller to catch {@code DataIntegrityViolationException}.</p>
     *
     * @param ipAddress the IP address string to check for existence
     * @return {@code true} if at least one device with this IP address exists,
     *         {@code false} otherwise
     */
    boolean existsByIpAddress(String ipAddress);

    /**
     * Returns a status-grouped device count summary, ranked by frequency.
     *
     * <p>This is a native SQL query (not JPQL), indicated by {@code nativeQuery = true}.
     * Native queries are used here because the {@code RANK()} window function is not
     * part of the JPQL specification and cannot be expressed with Spring Data's
     * derived-query or JPQL {@code @Query} syntax. The raw SQL is passed directly to
     * the underlying database (H2 in this project, which supports {@code RANK()}).</p>
     *
     * <p><b>SQL breakdown:</b></p>
     * <pre>
     * SELECT
     *     status,                                          -- The device status value (ONLINE/OFFLINE/UNKNOWN)
     *     COUNT(*) AS device_count,                       -- Number of devices in this status group
     *     RANK() OVER (ORDER BY COUNT(*) DESC) AS rank    -- Window function: assigns rank 1 to the most
     *                                                     --   common status, 2 to the next, etc.
     *                                                     --   Ties receive the same rank (e.g., two groups
     *                                                     --   with equal counts both get rank 1, and the
     *                                                     --   next group gets rank 3, not rank 2).
     * FROM devices
     * GROUP BY status                                     -- Collapse all rows with the same status into one
     * ORDER BY device_count DESC                          -- Return most-common statuses first
     * </pre>
     *
     * <p><b>Why RANK() vs COUNT alone?</b> The window function adds relative ordering
     * context to each row without requiring a second query. Callers can immediately
     * tell which status dominates the fleet (rank 1) and how the others compare, useful
     * for dashboard-style summaries. {@code RANK()} (as opposed to {@code ROW_NUMBER()})
     * handles ties gracefully by giving tied groups the same rank.</p>
     *
     * <p><b>Return type:</b> Each element of the returned {@code List<Object[]>} is a
     * three-element array corresponding to the three selected columns:
     * <ul>
     *   <li>{@code Object[0]} — status string (e.g., {@code "ONLINE"})</li>
     *   <li>{@code Object[1]} — device count ({@code Long})</li>
     *   <li>{@code Object[2]} — rank ({@code Long})</li>
     * </ul>
     * The caller (typically a service or controller method) is responsible for
     * casting each element to the appropriate type.</p>
     *
     * @return a list of Object arrays, one per distinct status, ordered by descending
     *         device count, each array containing [status, device_count, rank]
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
