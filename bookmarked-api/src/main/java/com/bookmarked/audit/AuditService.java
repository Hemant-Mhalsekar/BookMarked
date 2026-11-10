package com.bookmarked.audit;

import com.bookmarked.common.ApiException;
import com.bookmarked.common.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The audit trail of staff actions: who did what, to which record, from where, and
 * (for the important changes) the value before and after. Rows are only ever added.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public record Entry(Long id, Long staffId, String staffName, String staffEmail, String action, String entityType,
                        String entityId, String oldValue, String newValue, String ipAddress, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Writes one entry in its own transaction, so it's kept even if the action it
     * describes failed (failed attempts are worth knowing about too). Never throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long staffId, String action, String entityType, String entityId, Object oldValue,
                       Object newValue, String ip) {
        try {
            jdbc.update("""
                    insert into audit_logs (staff_id, action, entity_type, entity_id, old_value, new_value, ip_address, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, staffId, cut(action, 60), cut(entityType, 40), cut(entityId == null ? "-" : entityId, 40),
                    toJson(oldValue), toJson(newValue), ip == null || ip.length() > 45 ? null : ip,
                    Timestamp.from(clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not write audit entry {} {} {} by staff {}", action, entityType, entityId, staffId, e);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Entry> search(Long staffId, String action, String entityType, String entityId,
                                      Instant from, Instant to, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (staffId != null) {
            where.append(" and a.staff_id = ?");
            args.add(staffId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" and a.action like ? escape '!'");
            args.add(like(action));
        }
        if (entityType != null && !entityType.isBlank()) {
            where.append(" and a.entity_type = ?");
            args.add(entityType.strip().toUpperCase());
        }
        if (entityId != null && !entityId.isBlank()) {
            where.append(" and a.entity_id = ?");
            args.add(entityId.strip());
        }
        if (from != null) {
            where.append(" and a.created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" and a.created_at < ?");
            args.add(Timestamp.from(to));
        }
        long total = jdbc.queryForObject("select count(*) from audit_logs a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Entry> rows = jdbc.query("""
                select a.id, a.staff_id, u.full_name, u.email, a.action, a.entity_type, a.entity_id,
                       a.old_value, a.new_value, a.ip_address, a.created_at
                from audit_logs a join users u on u.id = a.staff_id
                """ + where + " order by a.created_at desc, a.id desc limit ? offset ?",
                (rs, i) -> new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getTimestamp(11).toInstant()), pageArgs.toArray());
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"(unserialisable)\"";
        }
    }

    private static String like(String term) {
        String t = term.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + t + "%";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
