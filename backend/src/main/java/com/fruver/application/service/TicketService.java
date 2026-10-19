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
        return jdbc.sql("SELECT order_id, created_at, customer_name, customer_phone, customer_type, total, address, notes, delivery_person_name FROM sale WHERE id = :s")
                .param("s", saleId)
                .query((rs, n) -> new TicketData(props.printing().shopName(), rs.getLong(1),
                        rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(zone).toOffsetDateTime(),
                        rs.getString(3), rs.getString(4), rs.getString(5), lines, rs.getBigDecimal(6),
                        rs.getString(7), rs.getString(8), reprint, rs.getString(9))).single();
    }
}
