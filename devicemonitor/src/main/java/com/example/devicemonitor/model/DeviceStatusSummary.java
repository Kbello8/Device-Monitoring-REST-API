package com.example.devicemonitor.model;

/**
 * A read-only data object that holds the result of the status summary query.
 * It is NOT a JPA entity — it is never saved to the database.
 * Its sole purpose is to carry query results from the repository to the API response.
 *
 * CONCEPT — DTO (Data Transfer Object):
 * A DTO is a plain object used to move data between layers of the application.
 * Here, the repository runs a native SQL query that returns raw Object[] arrays
 * (not Device entities). This class wraps those raw results into a typed,
 * named structure that is easier and safer to work with upstream, and serializes
 * cleanly to JSON for the API consumer.
 *
 * CONCEPT — Why not return Device objects from the summary query?
 * The summary query uses GROUP BY and RANK() — it doesn't return individual
 * device rows. It returns aggregated data (status + count + rank), which doesn't
 * fit the Device model at all. A separate class is the right design.
 */
public class DeviceStatusSummary {

    private String status;      // The device status group (e.g., "ONLINE")
    private long deviceCount;   // How many devices have that status
    private long rank;          // Rank by count (1 = most common status)

    /**
     * Constructs a DeviceStatusSummary from a raw Object[] row returned by the
     * native SQL query in DeviceRepository.getStatusSummaryWithRank().
     *
     * CONCEPT — Native Query Result Mapping:
     * When JPA runs a native SQL query (plain SQL, not JPQL), it can't
     * automatically map results to typed objects the way it can for entity queries.
     * Instead it returns List<Object[]> — each Object[] is one row, and each
     * element is a column value. We manually pull columns out by index and cast them.
     *
     * CONCEPT — Casting to Number before longValue():
     * H2 (the in-memory database used here) may return numeric columns as Integer,
     * Long, or BigDecimal depending on the SQL expression. Casting to Number first
     * (the parent type of all numeric wrappers) and then calling .longValue() is
     * safer than casting directly to Long, which would throw a ClassCastException
     * if H2 returns an Integer instead.
     *
     * @param row Object array where: row[0]=status, row[1]=device_count, row[2]=rank
     */
    public DeviceStatusSummary(Object[] row){
        this.status = (String) row[0];                       // column 0: status string
        this.deviceCount = ((Number) row[1]).longValue();    // column 1: count(*) result
        this.rank = ((Number) row[2]).longValue();           // column 2: RANK() window result
    }

    // Getters only — this object is read-only by design (no setters needed)
    public String getStatus() {return status;}
    public long getDeviceCount() {return deviceCount;}
    public long getRank() {return rank;}
}
