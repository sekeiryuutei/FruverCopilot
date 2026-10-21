package com.fruver.application.service;

import com.fruver.application.dto.Quote;
import com.fruver.application.dto.QuoteLine;
import com.fruver.application.port.MessagingGateway;
import com.fruver.config.AppProperties;
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
 * y encola el trabajo de impresion. Soporta tanto modo POS detallado como comanda mayorista automática.
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
    private final AppProperties props;

    public SaleService(JdbcClient jdbc, OrderRepository orders, QuoteService quotes, AuditService audit,
                       MessagingGateway gateway, SseHub hub, AppProperties props) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.quotes = quotes;
        this.audit = audit;
        this.gateway = gateway;
        this.hub = hub;
        this.props = props;
    }

    @Transactional
    public SaleResult approve(long orderId, int expectedVersion, String user) {
        return approve(orderId, expectedVersion, null, user);
    }

    @Transactional
    public SaleResult approve(long orderId, int expectedVersion, Long deliveryPersonId, String user) {
        OrderRow o = orders.findForUpdate(orderId)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado"));

        // Idempotencia: un segundo clic o un segundo empleado recibe la venta ya creada
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

        boolean isWholesale = "WHOLESALE".equalsIgnoreCase(o.customerType()) || props.printing().autoPrintWholesale();
        if (!quote.complete() && !isWholesale) {
            List<String> details = new ArrayList<>();
            for (QuoteLine l : quote.lines()) {
                if (l.problem() != null) {
                    String pName = l.productName() != null ? l.productName() : ("Línea " + l.itemId());
                    details.add(pName + ": " + l.problem());
                }
            }
            if (items.isEmpty()) details.add("El pedido no tiene líneas");
            throw new ApiException(422, "ORDER_REQUIRES_REVIEW", "El pedido requiere revisión manual", details);
        }

        String targetPrinter = props.printing().currentTargetPrinter(props.timezone());
        BigDecimal total = quote.complete() ? quote.total() : null;

        long saleId = jdbc.sql("""
                INSERT INTO sale(order_id, customer_id, customer_name, customer_phone, address, notes, customer_type, price_type,
                                 input_type, total, approved_by, delivery_person_id, delivery_person_name, target_printer)
                VALUES (:o, :c, :n, :p, :a, :no, :ct, :pt, :it, :t, :u, :dpid, :dpname, :tp) RETURNING id
                """).param("o", orderId).param("c", o.customerId()).param("n", o.customerName()).param("p", o.customerPhone())
                .param("a", o.address()).param("no", o.notes()).param("ct", o.customerType()).param("pt", priceType.name())
                .param("it", o.inputType()).param("t", total).param("u", user)
                .param("dpid", dpId).param("dpname", dpName).param("tp", targetPrinter).query(Long.class).single();

        for (int i = 0; i < items.size(); i++) {
            ItemRow it = items.get(i);
            QuoteLine l = (quote.lines() != null && i < quote.lines().size()) ? quote.lines().get(i) : null;
            String pName = l != null && l.productName() != null ? l.productName() : (it.aiProductName() != null ? it.aiProductName() : it.mentioned());
            Long pid = l != null ? l.productId() : it.productId();
            String unit = l != null && l.unit() != null ? l.unit() : (it.unit() != null ? it.unit() : "UNIDAD");
            BigDecimal qty = l != null && l.quantity() != null ? l.quantity() : (it.quantity() != null ? it.quantity() : BigDecimal.ONE);
            BigDecimal catPrice = l != null ? l.catalogPrice() : null;
            BigDecimal appPrice = l != null ? l.appliedPrice() : it.manualPrice();
            BigDecimal subtotal = l != null ? l.subtotal() : null;
            boolean manual = l != null ? l.manual() : it.manualPrice() != null;

            jdbc.sql("""
                    INSERT INTO sale_item(sale_id, product_id, product_name, unit, quantity, price_type, catalog_price, applied_price,
                                          price_manually_changed, price_change_reason, subtotal)
                    VALUES (:s, :p, :n, :u, :q, :pt, :cp, :ap, :m, :r, :st)
                    """).param("s", saleId).param("p", pid).param("n", pName).param("u", unit)
                    .param("q", qty).param("pt", priceType.name()).param("cp", catPrice)
                    .param("ap", appPrice).param("m", manual).param("r", it.priceChangeReason())
                    .param("st", subtotal).update();
        }

        jdbc.sql("""
                UPDATE orders SET status = 'APPROVED', conversation_status = 'COMPLETED', approved_at = now(),
                       delivery_person_id = :dpid, delivery_person_name = :dpname, version = version + 1
                WHERE id = :id
                """).param("dpid", dpId).param("dpname", dpName).param("id", orderId).update();

        jdbc.sql("INSERT INTO print_job(sale_id, reprint, target_printer) VALUES (:s, FALSE, :tp)")
                .param("s", saleId).param("tp", targetPrinter).update();

        audit.log(user, "ORDER_APPROVED", orderId, saleId, Map.of(
                "targetPrinter", targetPrinter,
                "lines", items.size()));

        if (!isWholesale) {
            try {
                sendInvoiceWhatsApp(saleId, user);
            } catch (Exception ignored) {}
        }

        return new SaleResult(saleId, orderId, "PRINT_PENDING", total);
    }

    /** Auto-aprobación para comanda mayorista: crea venta y encola impresión al instante */
    @Transactional
    public SaleResult autoApproveWholesale(long orderId, boolean requiresVerification, String verificationReason, String user) {
        OrderRow o = orders.findForUpdate(orderId)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado"));

        if (o.saleId() != null) {
            return jdbc.sql("SELECT id, order_id, print_status, total FROM sale WHERE id = :id").param("id", o.saleId())
                    .query((rs, n) -> new SaleResult(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4))).single();
        }

        List<ItemRow> items = orders.items(orderId);
        PriceType priceType = PriceType.forCustomer(CustomerType.valueOf(o.customerType()));
        Quote quote = null;
        try {
            quote = quotes.quote(items, priceType);
        } catch (Exception ignored) {}

        BigDecimal total = (quote != null && quote.complete()) ? quote.total() : null;
        String targetPrinter = props.printing().currentTargetPrinter(props.timezone());

        long saleId = jdbc.sql("""
                INSERT INTO sale(order_id, customer_id, customer_name, customer_phone, address, notes, customer_type, price_type,
                                 input_type, total, approved_by, delivery_person_id, delivery_person_name, target_printer,
                                 requires_verification, verification_reason)
                VALUES (:o, :c, :n, :p, :a, :no, :ct, :pt, :it, :t, :u, :dpid, :dpname, :tp, :rv, :vr) RETURNING id
                """).param("o", orderId).param("c", o.customerId()).param("n", o.customerName()).param("p", o.customerPhone())
                .param("a", o.address()).param("no", o.notes()).param("ct", o.customerType()).param("pt", priceType.name())
                .param("it", o.inputType()).param("t", total).param("u", user)
                .param("dpid", o.deliveryPersonId()).param("dpname", o.deliveryPersonName())
                .param("tp", targetPrinter).param("rv", requiresVerification).param("vr", verificationReason)
                .query(Long.class).single();

        for (ItemRow it : items) {
            String pName = it.aiProductName() != null && !it.aiProductName().isBlank() ? it.aiProductName() : it.mentioned();
            if (pName == null || pName.isBlank()) pName = "Artículo";
            String unit = it.unit() != null ? it.unit() : (it.aiUnit() != null ? it.aiUnit() : "UNIDAD");
            BigDecimal qty = it.quantity() != null && it.quantity().compareTo(BigDecimal.ZERO) > 0
                    ? it.quantity()
                    : (it.aiQuantity() != null && it.aiQuantity().compareTo(BigDecimal.ZERO) > 0 ? it.aiQuantity() : BigDecimal.ONE);

            jdbc.sql("""
                    INSERT INTO sale_item(sale_id, product_id, product_name, unit, quantity, price_type, catalog_price, applied_price,
                                          price_manually_changed, price_change_reason, subtotal)
                    VALUES (:s, :p, :n, :u, :q, :pt, :cp, :ap, :m, :r, :st)
                    """).param("s", saleId).param("p", it.productId()).param("n", pName).param("u", unit)
                    .param("q", qty).param("pt", priceType.name()).param("cp", null)
                    .param("ap", it.manualPrice()).param("m", it.manualPrice() != null).param("r", it.priceChangeReason())
                    .param("st", null).update();
        }

        jdbc.sql("""
                UPDATE orders SET status = 'APPROVED', conversation_status = 'COMPLETED', approved_at = now(),
                       requires_verification = :rv, verification_reason = :vr, version = version + 1
                WHERE id = :id
                """).param("rv", requiresVerification).param("vr", verificationReason).param("id", orderId).update();

        jdbc.sql("INSERT INTO print_job(sale_id, reprint, target_printer) VALUES (:s, FALSE, :tp)")
                .param("s", saleId).param("tp", targetPrinter).update();

        audit.log(user, "ORDER_AUTO_APPROVED_WHOLESALE", orderId, saleId, Map.of(
                "targetPrinter", targetPrinter,
                "requiresVerification", requiresVerification,
                "lines", items.size()));

        return new SaleResult(saleId, orderId, "PRINT_PENDING", total);
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
        sb.append("🧾 *Fruver El Oasis - Factura de Venta*\n");
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
                    .append(" ").append(pName);
            if (subtotal != null && price != null) {
                sb.append(" — ").append(com.fruver.domain.service.Money.format(subtotal))
                  .append(" (").append(com.fruver.domain.service.Money.format(price)).append("/").append(unit).append(")");
            }
            sb.append("\n");
        }
        if (total != null) {
            sb.append("\n*TOTAL: ").append(com.fruver.domain.service.Money.format(total)).append(" COP*\n\n");
        }
        sb.append("¡Muchas gracias por su compra! Su pedido está preparado y listo para despacho 🛵💨");
        return sb.toString();
    }

    public void sendInvoiceWhatsApp(long saleId, String user) {
        var row = jdbc.sql("SELECT order_id, customer_phone FROM sale WHERE id = :id")
                .param("id", saleId)
                .query((rs, n) -> new String[]{ String.valueOf(rs.getLong(1)), rs.getString(2) })
                .optional().orElseThrow(() -> new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada"));

        long orderId = Long.parseLong(row[0]);
        String phone = row[1];
        if (phone == null || phone.isBlank()) return;

        String invoiceText = buildInvoiceWhatsAppText(saleId);
        var delivery = gateway.sendText(phone, invoiceText).name();
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

        if (phone != null && !phone.isBlank() && !props.printing().autoPrintWholesale()) {
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

    /** Reimprimir/reintentar NO crea otra venta: solo encola otro trabajo de impresion en la impresora actual. */
    @Transactional
    public void reprint(long saleId, String user) {
        Long orderId = jdbc.sql("SELECT order_id FROM sale WHERE id = :id FOR UPDATE").param("id", saleId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada"));
        String target = props.printing().currentTargetPrinter(props.timezone());
        jdbc.sql("INSERT INTO print_job(sale_id, reprint, target_printer) VALUES (:s, TRUE, :tp)")
                .param("s", saleId).param("tp", target).update();
        jdbc.sql("UPDATE sale SET print_status = 'PRINT_PENDING', print_error = NULL, target_printer = :tp WHERE id = :id")
                .param("tp", target).param("id", saleId).update();
        audit.log(user, "REPRINT_REQUESTED", orderId, saleId, Map.of("targetPrinter", target));
    }

    /** Reimprime buscando por ID de pedido o factura */
    @Transactional
    public Long reprintByOrderId(long orderId, String user) {
        Long saleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :o").param("o", orderId).query(Long.class).optional().orElse(null);
        if (saleId != null) {
            reprint(saleId, user);
            return saleId;
        }
        autoApproveWholesale(orderId, false, null, user);
        return jdbc.sql("SELECT id FROM sale WHERE order_id = :o").param("o", orderId).query(Long.class).single();
    }
}
