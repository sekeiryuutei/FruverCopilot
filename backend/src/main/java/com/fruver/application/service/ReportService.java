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
                .param("a", start).param("b", end).query((rs, n) -> Map.<String, Object>of("sales", rs.getLong(1), "revenue", rs.getBigDecimal(2) != null ? rs.getBigDecimal(2) : java.math.BigDecimal.ZERO)).single());
        out.put("byCustomerType", group("SELECT COALESCE(customer_type, 'RETAIL'), count(*), COALESCE(sum(total), 0) FROM sale WHERE created_at >= :a AND created_at < :b GROUP BY 1 ORDER BY 1", start, end));
        out.put("byInputType", group("SELECT COALESCE(input_type, 'MANUAL'), count(*), COALESCE(sum(total), 0) FROM sale WHERE created_at >= :a AND created_at < :b GROUP BY 1 ORDER BY 1", start, end));

        out.put("topProducts", jdbc.sql("""
                SELECT COALESCE(i.product_name, 'Producto'), COALESCE(i.unit, ''), COALESCE(sum(i.quantity), 0), COALESCE(sum(i.subtotal), 0)
                FROM sale_item i JOIN sale s ON s.id = i.sale_id WHERE s.created_at >= :a AND s.created_at < :b
                GROUP BY i.product_name, i.unit ORDER BY COALESCE(sum(i.subtotal), 0) DESC, COALESCE(sum(i.quantity), 0) DESC LIMIT 10
                """).param("a", start).param("b", end)
                .query((rs, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("product", rs.getString(1) != null ? rs.getString(1) : "Producto");
                    item.put("unit", rs.getString(2) != null ? rs.getString(2) : "");
                    item.put("quantity", rs.getBigDecimal(3) != null ? rs.getBigDecimal(3) : java.math.BigDecimal.ZERO);
                    item.put("revenue", rs.getBigDecimal(4) != null ? rs.getBigDecimal(4) : java.math.BigDecimal.ZERO);
                    return item;
                }).list());

        // Calidad de la IA y velocidad operativa (metricas para saber si el sistema realmente acelera el negocio)
        out.put("reviewStatus", jdbc.sql("SELECT COALESCE(review_status, 'SIN_REVISION'), count(*) FROM orders WHERE received_at >= :a AND received_at < :b GROUP BY 1 ORDER BY 1")
                .param("a", start).param("b", end).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString(1) != null ? rs.getString(1) : "SIN_REVISION");
                    m.put("orders", rs.getLong(2));
                    return m;
                }).list());
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
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", rs.getString(1) != null ? rs.getString(1) : "GENERAL");
                    m.put("sales", rs.getLong(2));
                    m.put("revenue", rs.getBigDecimal(3) != null ? rs.getBigDecimal(3) : java.math.BigDecimal.ZERO);
                    return m;
                }).list();
    }
}
