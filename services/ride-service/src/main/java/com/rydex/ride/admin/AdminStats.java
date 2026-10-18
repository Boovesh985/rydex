package com.rydex.ride.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only aggregates over {@code rides} for the admin dashboard. */
@Component
public class AdminStats {

    /** Heatmap cell size in degrees: about 550 m north-south. */
    static final double CELL_DEG = 0.005;
    private static final int MAX_CELLS = 2000;

    private final JdbcClient jdbc;
    private final Clock clock;

    AdminStats(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param inFlight       rides not finished yet, by status (all time)
     * @param requested      rides requested in the window, and their outcome so far by status
     * @param matchRate      share of the window's finished searches that found a driver (null if none finished)
     * @param avgAssignS     median seconds from request to driver assigned (the name predates the median)
     * @param avgPickupS     median seconds from driver assigned to driver arrived
     * @param revenue        fares of rides completed in the window (INR)
     * @param avgFare        mean fare of those rides
     * @param aiSuggestions  smart suggestions shown to riders in the window
     * @param aiBooked       how many of them the rider booked with
     */
    public record Stats(int hours, Instant since, Map<String, Long> inFlight, long requested,
                        Map<String, Long> byStatus, long completed, long cancelled, long noDriverFound,
                        Double matchRate, Integer avgAssignS, Integer avgPickupS, BigDecimal revenue,
                        BigDecimal avgFare, long riders, long drivers, long aiSuggestions, long aiBooked) {
    }

    public record DemandCell(double lat, double lng, long requests) {
    }

    private record Outcome(long matched, long searched, Double assignS, Double pickupS) {
    }

    private record Money(long completed, BigDecimal revenue, BigDecimal avgFare) {
    }

    @Transactional(readOnly = true)
    public Stats stats(int hours) {
        Instant since = clock.instant().minus(Duration.ofHours(hours));
        Timestamp from = Timestamp.from(since);

        Map<String, Long> inFlight = new LinkedHashMap<>();
        for (String s : List.of("REQUESTED", "DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_PROGRESS")) {
            inFlight.put(s, 0L);
        }
        jdbc.sql("""
                        SELECT status, count(*) AS n FROM rides
                        WHERE status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS')
                        GROUP BY status
                        """)
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list().forEach(e -> inFlight.put(e.getKey(), e.getValue()));

        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM rides WHERE requested_at >= :from GROUP BY status ORDER BY status")
                .param("from", from)
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list().forEach(e -> byStatus.put(e.getKey(), e.getValue()));
        long requested = byStatus.values().stream().mapToLong(Long::longValue).sum();

        var outcome = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE assigned_at IS NOT NULL) AS matched,
                               count(*) FILTER (WHERE assigned_at IS NOT NULL OR status = 'NO_DRIVER_FOUND') AS searched,
                               -- medians, not means: one ride left hanging for hours (a driver who vanished)
                               -- would otherwise swamp a day of normal pickups
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM assigned_at - requested_at))::float8 AS assign_s,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM arrived_at - assigned_at))::float8 AS pickup_s
                        FROM rides WHERE requested_at >= :from
                        """)
                .param("from", from)
                .query((rs, i) -> new Outcome(rs.getLong("matched"), rs.getLong("searched"),
                        rs.getObject("assign_s", Double.class), rs.getObject("pickup_s", Double.class)))
                .single();

        var money = jdbc.sql("""
                        SELECT count(*) AS completed, coalesce(sum(final_fare), 0) AS revenue, avg(final_fare) AS avg_fare
                        FROM rides WHERE status = 'COMPLETED' AND completed_at >= :from
                        """)
                .param("from", from)
                .query((rs, i) -> new Money(rs.getLong("completed"), rs.getBigDecimal("revenue"), rs.getBigDecimal("avg_fare")))
                .single();
        long cancelled = jdbc.sql("SELECT count(*) FROM rides WHERE status = 'CANCELLED' AND cancelled_at >= :from")
                .param("from", from).query(Long.class).single();

        var people = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE role = 'RIDER') AS riders,
                               count(*) FILTER (WHERE role = 'DRIVER') AS drivers
                        FROM users
                        """)
                .query((rs, i) -> new long[] {rs.getLong("riders"), rs.getLong("drivers")})
                .single();

        var ai = jdbc.sql("""
                        SELECT count(*) AS shown, count(*) FILTER (WHERE accepted AND ride_id IS NOT NULL) AS booked
                        FROM ai_suggestion_log WHERE created_at >= :from
                        """)
                .param("from", from)
                .query((rs, i) -> new long[] {rs.getLong("shown"), rs.getLong("booked")})
                .single();

        return new Stats(hours, since, inFlight, requested, byStatus, money.completed(), cancelled,
                byStatus.getOrDefault("NO_DRIVER_FOUND", 0L),
                outcome.searched() == 0 ? null : (double) outcome.matched() / outcome.searched(),
                seconds(outcome.assignS()), seconds(outcome.pickupS()),
                money.revenue().setScale(2, RoundingMode.HALF_UP),
                money.avgFare() == null ? null : money.avgFare().setScale(2, RoundingMode.HALF_UP),
                people[0], people[1], ai[0], ai[1]);
    }

    /** Requests in the window, counted per grid cell of {@link #CELL_DEG} degrees around the cell's centre. */
    @Transactional(readOnly = true)
    public List<DemandCell> demand(int hours) {
        Instant since = clock.instant().minus(Duration.ofHours(hours));
        return jdbc.sql("""
                        SELECT round(pickup_lat / :cell) * :cell AS lat, round(pickup_lng / :cell) * :cell AS lng,
                               count(*) AS n
                        FROM rides WHERE requested_at >= :from
                        GROUP BY 1, 2 ORDER BY n DESC LIMIT :limit
                        """)
                .param("cell", CELL_DEG)
                .param("from", Timestamp.from(since))
                .param("limit", MAX_CELLS)
                .query((rs, i) -> new DemandCell(round(rs.getDouble("lat")), round(rs.getDouble("lng")), rs.getLong("n")))
                .list();
    }

    private static Integer seconds(Double s) {
        return s == null ? null : (int) Math.round(s);
    }

    private static double round(double deg) {
        return Math.round(deg * 1e5) / 1e5;
    }
}
