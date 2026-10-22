package com.fruver.application.service;

import com.fruver.config.AppProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** Arma los datos del ticket desde el SNAPSHOT de la venta (nunca desde precios actuales). */
@Service
public class TicketService {

    private final JdbcClient jdbc;
    private final AppProperties props;

    public TicketService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public TicketData build(long saleId, boolean reprint) {
        List<TicketData.Line> lines = jdbc.sql("SELECT product_name, quantity, unit, applied_price, subtotal FROM sale_item WHERE sale_id = :s ORDER BY id")
                .param("s", saleId)
                .query((rs, n) -> new TicketData.Line(rs.getString(1), rs.getBigDecimal(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5))).list();
        ZoneId zone = ZoneId.of(props.timezone());
        return jdbc.sql("""
                SELECT order_id, created_at, customer_name, customer_phone, customer_type, total, address, notes,
                       delivery_person_name, COALESCE(requires_verification, FALSE), verification_reason
                FROM sale WHERE id = :s
                """)
                .param("s", saleId)
                .query((rs, n) -> new TicketData(
                        props.printing().shopName(),
                        rs.getLong(1),
                        rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(zone).toOffsetDateTime(),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        lines,
                        rs.getBigDecimal(6),
                        rs.getString(7),
                        rs.getString(8),
                        reprint,
                        rs.getString(9),
                        rs.getBoolean(10),
                        rs.getString(11))).single();
    }

    public TicketData buildFromOrder(long orderId) {
        List<TicketData.Line> lines = jdbc.sql("""
                SELECT COALESCE(p.name, oi.ai_product_name, oi.mentioned, 'Artículo'),
                       COALESCE(oi.quantity, oi.ai_quantity, 1),
                       COALESCE(oi.unit, oi.ai_unit, ''),
                       oi.manual_price,
                       NULL
                FROM order_item oi
                LEFT JOIN product p ON p.id = oi.product_id
                WHERE oi.order_id = :o ORDER BY oi.line_no, oi.id
                """).param("o", orderId)
                .query((rs, n) -> new TicketData.Line(
                        rs.getString(1),
                        rs.getBigDecimal(2),
                        rs.getString(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5)
                )).list();

        ZoneId zone = ZoneId.of(props.timezone());
        return jdbc.sql("""
                SELECT o.id, o.received_at, COALESCE(o.customer_name, c.name, 'Cliente'),
                       COALESCE(o.customer_phone, c.phone, ''), COALESCE(c.customer_type, 'RETAIL'),
                       o.address, o.notes, o.delivery_person_name,
                       COALESCE(o.requires_verification, FALSE), o.verification_reason
                FROM orders o
                LEFT JOIN customer c ON c.id = o.customer_id
                WHERE o.id = :o
                """)
                .param("o", orderId)
                .query((rs, n) -> new TicketData(
                        props.printing().shopName(),
                        rs.getLong(1),
                        rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(zone).toOffsetDateTime(),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        lines,
                        null,
                        rs.getString(6),
                        rs.getString(7),
                        false,
                        rs.getString(8),
                        rs.getBoolean(9),
                        rs.getString(10))).single();
    }
}
