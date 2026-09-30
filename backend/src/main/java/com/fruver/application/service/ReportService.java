package com.fruver.application.service;

import com.fruver.config.AppProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class ReportService {

    private final JdbcClient jdbc;
    private final ZoneId zone;

    public ReportService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.timezone());
    }

    public Map<String, Object> sales(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now(zone) : from;
        LocalDate t = to == null ? f : to;
        var start = f.atStartOfDay(zone).toOffsetDateTime();
        var end = t.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());

        out.put("totals", jdbc.sql("SELECT count(*) AS sales, COALESCE(sum(total), 0) AS revenue FROM sale WHERE created_at >= :a AND created_at < :b")
                .param("a", start).param("b", end).query((rs, n) -> Map.of("sales", rs.getLong(1), "revenue", rs.getBigDecimal(2))).single());
        out.put("byCustomerType", group("SELECT customer_type, count(*), COALESCE(sum(total), 0) FROM sale WHERE created_at >= :a AND created_at < :b GROUP BY 1 ORDER BY 1", start, end));
        out.put("byInputType", group("SELECT input_type, count(*), COALESCE(sum(total), 0) FROM sale WHERE created_at >= :a AND created_at < :b GROUP BY 1 ORDER BY 1", start, end));

        out.put("topProducts", jdbc.sql("""
                SELECT i.product_name, i.unit, sum(i.quantity), sum(i.subtotal)
                FROM sale_item i JOIN sale s ON s.id = i.sale_id WHERE s.created_at >= :a AND s.created_at < :b
                GROUP BY i.product_name, i.unit ORDER BY sum(i.subtotal) DESC LIMIT 10
                """).param("a", start).param("b", end)
                .query((rs, n) -> Map.of("product", rs.getString(1), "unit", rs.getString(2), "quantity", rs.getBigDecimal(3), "revenue", rs.getBigDecimal(4))).list());

        // Calidad de la IA y velocidad operativa (metricas para saber si el sistema realmente acelera el negocio)
        out.put("reviewStatus", jdbc.sql("SELECT COALESCE(review_status, 'SIN_REVISION'), count(*) FROM orders WHERE received_at >= :a AND received_at < :b GROUP BY 1 ORDER BY 1")
                .param("a", start).param("b", end).query((rs, n) -> Map.of("status", rs.getString(1), "orders", rs.getLong(2))).list());
        out.put("orders", jdbc.sql("""
                SELECT count(*), count(*) FILTER (WHERE corrected), count(*) FILTER (WHERE ai_status IN ('FAILED','TRANSCRIPTION_FAILED')),
                       COALESCE(avg(extract(epoch FROM approved_at - received_at)) FILTER (WHERE approved_at IS NOT NULL), 0)
                FROM orders WHERE received_at >= :a AND received_at < :b
                """).param("a", start).param("b", end).query((rs, n) -> {
                    long total = rs.getLong(1), corrected = rs.getLong(2);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("received", total);
                    m.put("correctedByHumans", corrected);
                    m.put("correctedPercent", total == 0 ? 0 : Math.round(corrected * 1000.0 / total) / 10.0);
                    m.put("aiFailures", rs.getLong(3));
                    m.put("avgSecondsReceivedToApproved", Math.round(rs.getDouble(4)));
                    return m;
                }).single());
        out.put("avgSecondsApprovedToPrinted", jdbc.sql("SELECT COALESCE(avg(extract(epoch FROM printed_at - created_at)), 0) FROM sale WHERE created_at >= :a AND created_at < :b AND printed_at IS NOT NULL")
                .param("a", start).param("b", end).query((rs, n) -> Math.round(rs.getDouble(1))).single());
        return out;
    }

    private List<Map<String, Object>> group(String sql, Object start, Object end) {
        return jdbc.sql(sql).param("a", start).param("b", end)
                .query((rs, n) -> Map.<String, Object>of("key", rs.getString(1), "sales", rs.getLong(2), "revenue", rs.getBigDecimal(3))).list();
    }
}
