package com.bookmarked.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-side helpers: per-item status history and the shipments of an order. */
@Component
public class OrderTimelines {

    public record TimelineEntry(String status, String note, Instant at) {
    }

    public record ShipmentView(Long id, String courierName, String trackingNumber, Instant shippedAt,
                               LocalDate expectedDelivery) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public OrderTimelines(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public Map<Long, List<TimelineEntry>> forItems(Collection<Long> itemIds) {
        Map<Long, List<TimelineEntry>> result = new HashMap<>();
        if (itemIds.isEmpty()) {
            return result;
        }
        named.query("""
                select order_item_id, status, note, changed_at from order_status_history
                where order_item_id in (:ids) order by changed_at, id
                """, new MapSqlParameterSource("ids", itemIds), rs -> {
            result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                    .add(new TimelineEntry(rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
        });
        return result;
    }

    public Map<Long, ShipmentView> shipments(Long orderId) {
        Map<Long, ShipmentView> result = new HashMap<>();
        jdbc.query("""
                select id, courier_name, tracking_number, shipped_at, expected_delivery from shipments
                where order_id = ? order by shipped_at, id
                """, rs -> {
            java.sql.Date expected = rs.getDate(5);
            result.put(rs.getLong(1), new ShipmentView(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getTimestamp(4).toInstant(), expected == null ? null : expected.toLocalDate()));
        }, orderId);
        return result;
    }
}
