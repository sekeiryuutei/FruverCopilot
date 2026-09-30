package com.fruver.application.service;

import com.fruver.config.AppProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class SalesQueryService {

    public record SaleSummary(long id, long orderId, String customerName, String phoneMasked, String customerType,
                              String inputType, BigDecimal total, String printStatus, String printError,
                              String approvedBy, OffsetDateTime createdAt) {}

    public record SaleItemView(String productName, String unit, BigDecimal quantity, BigDecimal catalogPrice,
                               BigDecimal appliedPrice, boolean manual, BigDecimal subtotal) {}

    private final JdbcClient jdbc;
    private final ZoneId zone;

    public SalesQueryService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.timezone());
    }

    /** Filtros: rango de fechas, tipo de cliente, producto y texto libre (cliente, telefono, direccion). */
    public List<SaleSummary> search(LocalDate from, LocalDate to, String customerType, Long productId, String q) {
        LocalDate f = from == null ? LocalDate.now(zone) : from;
        LocalDate t = to == null ? f : to;
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.order_id, s.customer_name, s.customer_phone, s.customer_type, s.input_type, s.total,
                       s.print_status, s.print_error, s.approved_by, s.created_at
                FROM sale s WHERE s.created_at >= :from AND s.created_at < :to
                """);
        Map<String, Object> p = new HashMap<>();
        p.put("from", f.atStartOfDay(zone).toOffsetDateTime());
        p.put("to", t.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if (customerType != null && !customerType.isBlank()) { sql.append(" AND s.customer_type = :ct"); p.put("ct", customerType); }
        if (productId != null) { sql.append(" AND EXISTS (SELECT 1 FROM sale_item i WHERE i.sale_id = s.id AND i.product_id = :pid)"); p.put("pid", productId); }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (s.customer_name ILIKE :q OR s.customer_phone ILIKE :q OR s.address ILIKE :q)");
            p.put("q", "%" + q.strip() + "%");
        }
        sql.append(" ORDER BY s.id DESC LIMIT 500");
        return jdbc.sql(sql.toString()).params(p).query((rs, n) -> new SaleSummary(rs.getLong(1), rs.getLong(2), rs.getString(3),
                Phones.mask(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class))).list();
    }

    public List<SaleItemView> items(long saleId) {
        return jdbc.sql("SELECT product_name, unit, quantity, catalog_price, applied_price, price_manually_changed, subtotal FROM sale_item WHERE sale_id = :s ORDER BY id")
                .param("s", saleId).query((rs, n) -> new SaleItemView(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBoolean(6), rs.getBigDecimal(7))).list();
    }
}
