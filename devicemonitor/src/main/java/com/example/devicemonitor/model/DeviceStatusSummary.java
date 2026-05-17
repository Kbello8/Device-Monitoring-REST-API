package com.example.devicemonitor.model;

/**
 * Read-only projection that holds an aggregated count of devices per status,
 * together with a popularity rank derived from those counts.
 *
 * <p>This class is <em>not</em> a JPA entity — it is a lightweight data
 * container (a "result-set DTO") populated directly from a native SQL query
 * in {@code DeviceRepository}. The query uses a window function:
 * <pre>
 *   RANK() OVER (ORDER BY COUNT(*) DESC)
 * </pre>
 * to assign rank 1 to the status with the most devices, rank 2 to the next,
 * and so on. Ties receive the same rank, matching standard SQL ranking
 * semantics.
 *
 * <p><b>Why a native query + manual constructor?</b>
 * Spring Data JPA's {@code @Query} with {@code nativeQuery=true} returns rows
 * as {@code Object[]} arrays rather than typed projections, so this class
 * accepts an {@code Object[]} and performs the type casting itself. This keeps
 * the mapping logic co-located with the DTO rather than scattered across the
 * repository or service layer.
 *
 * <p>Instances are created by {@code DeviceRepository.getDeviceStatusSummary()}
 * and surfaced to callers via {@code DeviceService}, which streams them to the
 * REST controller for the status-summary endpoint.
 */
public class DeviceStatusSummary {

    /** The string name of the device status (e.g. "ONLINE", "OFFLINE", "UNKNOWN"). */
    private String status;

    /** Total number of devices currently in this status. */
    private long deviceCount;

    /**
     * Popularity rank of this status group, where 1 = most devices.
     * Computed by the SQL {@code RANK()} window function in the repository
     * query, so no application-side sorting is needed.
     */
    private long rank;

    /**
     * Constructs a summary from a raw JDBC result-set row.
     *
     * <p>Column ordering matches the {@code SELECT} clause in the native query:
     * <ol>
     *   <li>{@code row[0]} — status name ({@code String})</li>
     *   <li>{@code row[1]} — device count ({@code Number}, cast to {@code long})</li>
     *   <li>{@code row[2]} — rank ({@code Number}, cast to {@code long})</li>
     * </ol>
     *
     * <p>The counts and rank are retrieved as {@code Number} (the common
     * super-type for {@code Integer}, {@code Long}, {@code BigDecimal}, etc.)
     * because different JDBC drivers may return different numeric sub-types
     * for the same SQL expression. Calling {@code .longValue()} normalizes
     * them all to a primitive {@code long}.
     *
     * @param row a three-element array from the native SQL result set
     */
    public DeviceStatusSummary(Object[] row){
        // row[0] is the status string — safe to cast directly since the DB column is VARCHAR.
        this.status = (String) row[0];

        // row[1] is a COUNT(*) result; driver type varies (Integer, Long, BigInteger),
        // so we upcast to Number and normalize to long.
        this.deviceCount = ((Number) row[1]).longValue();

        // row[2] is the RANK() window-function result; same driver-type caveat applies.
        this.rank = ((Number) row[2]).longValue();
    }

    /** Returns the status label (e.g. {@code "ONLINE"}). */
    public String getStatus() {return status;}

    /** Returns the number of devices currently in this status. */
    public long getDeviceCount() {return deviceCount;}

    /**
     * Returns the rank of this status group by device count (1 = largest group).
     * Rank values are contiguous unless there is a tie, in which case ranks
     * are skipped per SQL {@code RANK()} semantics.
     */
    public long getRank() {return rank;}
}
