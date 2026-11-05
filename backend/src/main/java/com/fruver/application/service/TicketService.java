package com.fruver.application.service;

import com.fruver.config.AppProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Arma los datos del ticket desde el SNAPSHOT de la venta (nunca desde precios actuales). */
@Service
public class TicketService {

    private final JdbcClient jdbc;
    private final AppProperties props;
    private final PrinterConfigService printerConfig;

    public TicketService(JdbcClient jdbc, AppProperties props) {
        this(jdbc, props, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TicketService(JdbcClient jdbc, AppProperties props,
                         @org.springframework.beans.factory.annotation.Autowired(required = false) PrinterConfigService printerConfig) {
        this.jdbc = jdbc;
        this.props = props;
        this.printerConfig = printerConfig;
    }

    public static OffsetDateTime calculateCommercialDate(OffsetDateTime at, String startStr, String endStr) {
        if (at == null) return null;
        try {
            java.time.LocalTime start = java.time.LocalTime.parse(startStr != null ? startStr.trim() : "04:00");
            java.time.LocalTime end = java.time.LocalTime.parse(endStr != null ? endStr.trim() : "19:00");
            java.time.LocalTime time = at.toLocalTime();
            // Horario nocturno del Fruver: desde las 19:00 (scheduleOfficeEnd) en adelante y hasta antes de las 04:00 (scheduleOfficeStart)
            boolean isNight = (time.compareTo(end) >= 0 || time.compareTo(start) < 0);
            return isNight ? at.plusDays(1) : at;
        } catch (Exception e) {
            return at;
        }
    }

    public TicketData build(long saleId, boolean reprint) {
        List<TicketData.Line> lines = jdbc.sql("SELECT product_name, quantity, unit, applied_price, subtotal FROM sale_item WHERE sale_id = :s ORDER BY id")
                .param("s", saleId)
                .query((rs, n) -> new TicketData.Line(rs.getString(1), rs.getBigDecimal(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5))).list();
        ZoneId zone = ZoneId.of(props.timezone());
        var cfg = (printerConfig != null) ? printerConfig.get() : null;
        String startStr = (cfg != null) ? cfg.scheduleOfficeStart() : "04:00";
        String endStr = (cfg != null) ? cfg.scheduleOfficeEnd() : "19:00";

        return jdbc.sql("""
                SELECT order_id, created_at, customer_name, customer_phone, customer_type, total, address, notes,
                       delivery_person_name, COALESCE(requires_verification, FALSE), verification_reason, input_type,
                       invoice_number
                FROM sale WHERE id = :s
                """)
                .param("s", saleId)
                .query((rs, n) -> {
                    long orderId = rs.getLong(1);
                    OffsetDateTime realAt = rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(zone).toOffsetDateTime();
                    OffsetDateTime commercialAt = calculateCommercialDate(realAt, startStr, endStr);
                    String resolvedOrigin = resolveOrigin(orderId, rs.getString(12));
                    Long invNum = rs.getObject(13) != null ? rs.getLong(13) : null;
                    return new TicketData(
                            props.printing().shopName(),
                            orderId,
                            realAt,
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
                            rs.getString(11),
                            resolvedOrigin,
                            invNum,
                            commercialAt);
                }).single();
    }

    public TicketData buildFromOrder(long orderId) {
        List<TicketData.Line> lines = jdbc.sql("""
                SELECT COALESCE(p.name, oi.ai_product_name, oi.mentioned, 'Artículo'),
                       COALESCE(oi.quantity, oi.ai_quantity, 1),
                       COALESCE(oi.unit, oi.ai_unit, ''),
                       oi.manual_price,
                       NULL,
                       oi.observation
                FROM order_item oi
                LEFT JOIN product p ON p.id = oi.product_id
                WHERE oi.order_id = :o ORDER BY oi.line_no, oi.id
                """).param("o", orderId)
                .query((rs, n) -> new TicketData.Line(
                        SaleService.buildProductNameWithNote(rs.getString(1), rs.getString(6)),
                        rs.getBigDecimal(2),
                        rs.getString(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5)
                )).list();

        ZoneId zone = ZoneId.of(props.timezone());
        var cfg = (printerConfig != null) ? printerConfig.get() : null;
        String startStr = (cfg != null) ? cfg.scheduleOfficeStart() : "04:00";
        String endStr = (cfg != null) ? cfg.scheduleOfficeEnd() : "19:00";

        return jdbc.sql("""
                SELECT o.id, o.received_at, COALESCE(o.customer_name, c.name, 'Cliente'),
                       COALESCE(o.customer_phone, c.phone, ''), COALESCE(c.customer_type, 'RETAIL'),
                       o.address, o.notes, o.delivery_person_name,
                       COALESCE(o.requires_verification, FALSE), o.verification_reason, o.input_type
                FROM orders o
                LEFT JOIN customer c ON c.id = o.customer_id
                WHERE o.id = :o
                """)
                .param("o", orderId)
                .query((rs, n) -> {
                    long oId = rs.getLong(1);
                    OffsetDateTime realAt = rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(zone).toOffsetDateTime();
                    OffsetDateTime commercialAt = calculateCommercialDate(realAt, startStr, endStr);
                    String resolvedOrigin = resolveOrigin(oId, rs.getString(11));
                    return new TicketData(
                            props.printing().shopName(),
                            oId,
                            realAt,
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
                            rs.getString(10),
                            resolvedOrigin,
                            null,
                            commercialAt);
                }).single();
    }
    private String resolveOrigin(long orderId, String fallbackInputType) {
        if (orderId <= 0) return fallbackInputType;
        try {
            boolean hasAudio = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS(SELECT 1 FROM audio WHERE order_id = :o)")
                    .param("o", orderId).query(Boolean.class).single());
            boolean hasImage = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS(SELECT 1 FROM media_image WHERE order_id = :o)")
                    .param("o", orderId).query(Boolean.class).single());
            boolean hasText = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS(SELECT 1 FROM conversation_message WHERE order_id = :o AND direction = 'INBOUND' AND message_type = 'TEXT' AND content IS NOT NULL AND TRIM(content) <> '')")
                    .param("o", orderId).query(Boolean.class).single());

            List<String> origins = new ArrayList<>();
            if (hasAudio) origins.add("AUDIO");
            if (hasImage) origins.add("IMAGEN");
            if (hasText) origins.add("TEXTO");

            if (origins.isEmpty()) {
                return fallbackInputType;
            }
            if (origins.size() == 1 && origins.contains("TEXTO")) {
                return "TEXT"; // solo texto: no mostrar indicador
            }
            return String.join("+", origins);
        } catch (Exception e) {
            return fallbackInputType;
        }
    }
}
