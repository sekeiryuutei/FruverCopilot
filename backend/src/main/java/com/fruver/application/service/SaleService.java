package com.fruver.application.service;

import com.fruver.application.dto.Quote;
import com.fruver.application.dto.QuoteLine;
import com.fruver.application.port.MessagingGateway;
import com.fruver.domain.enums.CustomerType;
import com.fruver.domain.enums.PriceType;
import com.fruver.infrastructure.persistence.OrderRepository;
import com.fruver.infrastructure.persistence.Rows.ItemRow;
import com.fruver.infrastructure.persistence.Rows.OrderRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "APROBAR E IMPRIMIR": valida, determina precios, recalcula y persiste la venta con snapshot,
 * y encola el trabajo de impresion. Todo en UNA transaccion sin llamadas externas.
 * La impresora nunca participa de esta transaccion: si falla despues, la venta sigue CONFIRMADA.
 */
@Service
public class SaleService {

    public record SaleResult(long saleId, long orderId, String printStatus, BigDecimal total) {}

    private final JdbcClient jdbc;
    private final OrderRepository orders;
    private final QuoteService quotes;
    private final AuditService audit;
    private final MessagingGateway gateway;
    private final SseHub hub;

    public SaleService(JdbcClient jdbc, OrderRepository orders, QuoteService quotes, AuditService audit,
                       MessagingGateway gateway, SseHub hub) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.quotes = quotes;
        this.audit = audit;
        this.gateway = gateway;
        this.hub = hub;
    }

    @Transactional
    public SaleResult approve(long orderId, int expectedVersion, String user) {
        return approve(orderId, expectedVersion, null, user);
    }

    @Transactional
    public SaleResult approve(long orderId, int expectedVersion, Long deliveryPersonId, String user) {
        OrderRow o = orders.findForUpdate(orderId)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado"));

        // Idempotencia: un segundo clic o un segundo empleado recibe la venta ya creada, nunca una segunda venta.
        if (o.saleId() != null) {
            return jdbc.sql("SELECT id, order_id, print_status, total FROM sale WHERE id = :id").param("id", o.saleId())
                    .query((rs, n) -> new SaleResult(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4))).single();
        }
        if (!"REVIEW".equals(o.status())) {
            throw new ApiException(409, "ORDER_NOT_APPROVABLE", "El pedido no esta listo para aprobarse (estado " + o.status() + ")");
        }
        if (o.version() != expectedVersion) {
            throw new ApiException(409, "ORDER_CONFLICT", "El pedido cambio mientras lo revisabas. Recarga y vuelve a aprobar.");
        }

        Long dpId = deliveryPersonId != null ? deliveryPersonId : o.deliveryPersonId();
        String dpName = o.deliveryPersonName();
        if (deliveryPersonId != null) {
            dpName = jdbc.sql("SELECT name FROM delivery_person WHERE id = :id").param("id", deliveryPersonId)
                    .query(String.class).optional().orElse(null);
        }

        List<ItemRow> items = orders.items(orderId);
        PriceType priceType = PriceType.forCustomer(CustomerType.valueOf(o.customerType()));
        Quote quote = quotes.quote(items, priceType);
        if (!quote.complete()) {
            List<String> details = new ArrayList<>();
            for (QuoteLine l : quote.lines()) {
                if (l.problem() != null) {
                    String pName = l.productName() != null ? l.productName() : ("Línea " + l.itemId());
                    String expl = switch (l.problem()) {
                        case "UNIT_MISMATCH" -> "la unidad '" + l.unit() + "' no coincide con la del catálogo";
                        case "NO_QUANTITY" -> "cantidad inválida o vacía";
                        case "NO_UNIT" -> "falta la unidad de medida";
                        case "NO_PRICE" -> "no tiene precio configurado";
                        case "NO_PRODUCT" -> "producto no seleccionado";
                        case "INACTIVE_PRODUCT" -> "producto inactivo";
                        default -> l.problem();
                    };
                    details.add(pName + ": " + expl + " (" + l.problem() + ")");
                }
            }
            if (items.isEmpty()) details.add("El pedido no tiene líneas");
            throw new ApiException(422, "ORDER_REQUIRES_REVIEW", "El pedido requiere revisión manual", details);
        }

        long saleId = jdbc.sql("""
                INSERT INTO sale(order_id, customer_id, customer_name, customer_phone, address, notes, customer_type, price_type,
                                 input_type, total, approved_by, delivery_person_id, delivery_person_name)
                VALUES (:o, :c, :n, :p, :a, :no, :ct, :pt, :it, :t, :u, :dpid, :dpname) RETURNING id
                """).param("o", orderId).param("c", o.customerId()).param("n", o.customerName()).param("p", o.customerPhone())
                .param("a", o.address()).param("no", o.notes()).param("ct", o.customerType()).param("pt", priceType.name())
                .param("it", o.inputType()).param("t", quote.total()).param("u", user)
                .param("dpid", dpId).param("dpname", dpName).query(Long.class).single();

        for (int i = 0; i < items.size(); i++) {
            QuoteLine l = quote.lines().get(i);
            ItemRow it = items.get(i);
            jdbc.sql("""
                    INSERT INTO sale_item(sale_id, product_id, product_name, unit, quantity, price_type, catalog_price, applied_price,
                                          price_manually_changed, price_change_reason, subtotal)
                    VALUES (:s, :p, :n, :u, :q, :pt, :cp, :ap, :m, :r, :st)
                    """).param("s", saleId).param("p", l.productId()).param("n", l.productName()).param("u", l.unit())
                    .param("q", l.quantity()).param("pt", priceType.name()).param("cp", l.catalogPrice())
                    .param("ap", l.appliedPrice()).param("m", l.manual()).param("r", it.priceChangeReason())
                    .param("st", l.subtotal()).update();
        }

        jdbc.sql("""
                UPDATE orders SET status = 'APPROVED', conversation_status = 'COMPLETED', approved_at = now(),
                       delivery_person_id = :dpid, delivery_person_name = :dpname, version = version + 1
                WHERE id = :id
                """).param("dpid", dpId).param("dpname", dpName).param("id", orderId).update();
        jdbc.sql("INSERT INTO print_job(sale_id, reprint) VALUES (:s, FALSE)").param("s", saleId).update();
        audit.log(user, "ORDER_APPROVED", orderId, saleId, Map.of("total", quote.total().toPlainString(),
                "priceType", priceType.name(), "lines", items.size()));

        // Envío automático de factura al cliente por WhatsApp (fallos no revierten la venta)
        try {
            sendInvoiceWhatsApp(saleId, user);
        } catch (Exception ignored) {}

        return new SaleResult(saleId, orderId, "PRINT_PENDING", quote.total());
    }

    public String buildInvoiceWhatsAppText(long saleId) {
        var sale = jdbc.sql("SELECT id, order_id, customer_name, address, notes, total, delivery_person_name FROM sale WHERE id = :id")
                .param("id", saleId)
                .query((rs, n) -> new Object[] {
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7)
                }).optional().orElseThrow(() -> new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada"));

        long oId = (Long) sale[1];
        String name = (String) sale[2];
        String address = (String) sale[3];
        String notes = (String) sale[4];
        BigDecimal total = (BigDecimal) sale[5];
        String dpName = (String) sale[6];

        var items = jdbc.sql("SELECT product_name, unit, quantity, applied_price, subtotal FROM sale_item WHERE sale_id = :id ORDER BY id")
                .param("id", saleId)
                .query((rs, n) -> new Object[] {
                        rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)
                }).list();

        StringBuilder sb = new StringBuilder();
        sb.append("🧾 *FruverCopilot - Factura de Venta*\n");
        sb.append("Pedido #").append(oId).append("\n");
        if (name != null && !name.isBlank()) sb.append("👤 Cliente: ").append(name).append("\n");
        if (address != null && !address.isBlank()) sb.append("📍 Dirección: ").append(address).append("\n");
        if (notes != null && !notes.isBlank()) sb.append("📝 Horario/Notas: ").append(notes).append("\n");
        if (dpName != null && !dpName.isBlank()) sb.append("🛵 Repartidor: ").append(dpName).append("\n");
        sb.append("\n*Detalle de productos:*\n");
        for (var it : items) {
            String pName = (String) it[0];
            String unit = (String) it[1];
            BigDecimal qty = (BigDecimal) it[2];
            BigDecimal price = (BigDecimal) it[3];
            BigDecimal subtotal = (BigDecimal) it[4];
            sb.append("• ").append(com.fruver.domain.service.Money.qty(qty)).append(" ").append(unit)
                    .append(" ").append(pName)
                    .append(" — ").append(com.fruver.domain.service.Money.format(subtotal))
                    .append(" (").append(com.fruver.domain.service.Money.format(price)).append("/").append(unit).append(")\n");
        }
        sb.append("\n*TOTAL: ").append(com.fruver.domain.service.Money.format(total)).append(" COP*\n\n");
        sb.append("¡Muchas gracias por su compra! Su pedido está preparado y pronto saldrá a despacho 🛵💨");
        return sb.toString();
    }

    public void sendInvoiceWhatsApp(long saleId, String user) {
        var row = jdbc.sql("SELECT customer_phone, order_id FROM sale WHERE id = :id")
                .param("id", saleId)
                .query((rs, n) -> new String[]{ rs.getString(1), String.valueOf(rs.getLong(2)) })
                .optional().orElseThrow(() -> new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada"));
        String phone = row[0];
        long orderId = Long.parseLong(row[1]);
        if (phone == null || phone.isBlank()) {
            throw new ApiException(400, "NO_PHONE", "El pedido no tiene teléfono registrado");
        }
        String invoiceText = buildInvoiceWhatsAppText(saleId);
        String delivery;
        try {
            delivery = gateway.sendText(phone, invoiceText).name();
        } catch (Exception e) {
            delivery = "FAILED";
        }
        jdbc.sql("INSERT INTO conversation_message(order_id, direction, message_type, content, delivery_status) VALUES (:o, 'OUTBOUND', 'TEXT', :c, :d)")
                .param("o", orderId).param("c", invoiceText).param("d", delivery).update();
        audit.log(user, "INVOICE_WHATSAPP_SENT", orderId, saleId, Map.of("delivery", delivery));
    }

    @Transactional
    public void dispatch(long saleId, String user) {
        var sale = jdbc.sql("SELECT order_id, customer_phone, address, delivery_person_name FROM sale WHERE id = :id FOR UPDATE")
                .param("id", saleId)
                .query((rs, n) -> new Object[]{ rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4) })
                .optional().orElseThrow(() -> new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada"));
        long orderId = (Long) sale[0];
        String phone = (String) sale[1];
        String address = (String) sale[2];
        String dpName = (String) sale[3];

        jdbc.sql("UPDATE sale SET dispatched_at = now(), dispatched_by = :u WHERE id = :id").param("u", user).param("id", saleId).update();
        jdbc.sql("UPDATE orders SET dispatched_at = now(), dispatched_by = :u, version = version + 1 WHERE id = :id").param("u", user).param("id", orderId).update();
        audit.log(user, "ORDER_DISPATCHED", orderId, saleId, Map.of());

        if (phone != null && !phone.isBlank()) {
            String msg = "🛵💨 *¡Tu pedido va en camino!*\n"
                    + (dpName != null && !dpName.isBlank() ? "Tu repartidor es: *" + dpName + "*.\n" : "")
                    + "El domiciliario ha salido hacia tu dirección"
                    + (address != null && !address.isBlank() ? ": " + address : "") + ".\n¡Pronto llegará! Gracias por tu confianza.";
            try {
                var del = gateway.sendText(phone, msg).name();
                jdbc.sql("INSERT INTO conversation_message(order_id, direction, message_type, content, delivery_status) VALUES (:o, 'OUTBOUND', 'TEXT', :c, :d)")
                        .param("o", orderId).param("c", msg).param("d", del).update();
            } catch (Exception ignored) {}
        }
    }

    /** Reimprimir/reintentar NO crea otra venta: solo encola otro trabajo de impresion. */
    @Transactional
    public void reprint(long saleId, String user) {
        Long orderId = jdbc.sql("SELECT order_id FROM sale WHERE id = :id FOR UPDATE").param("id", saleId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada"));
        jdbc.sql("INSERT INTO print_job(sale_id, reprint) VALUES (:s, TRUE)").param("s", saleId).update();
        jdbc.sql("UPDATE sale SET print_status = 'PRINT_PENDING', print_error = NULL WHERE id = :id").param("id", saleId).update();
        audit.log(user, "REPRINT_REQUESTED", orderId, saleId, Map.of());
    }
}
