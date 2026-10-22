package com.fruver.application.service;

import com.fruver.application.dto.ItemView;
import com.fruver.application.dto.OrderView;
import com.fruver.application.dto.Quote;
import com.fruver.application.dto.QuoteLine;
import com.fruver.config.AppProperties;
import com.fruver.domain.enums.CustomerType;
import com.fruver.domain.enums.PriceType;
import com.fruver.domain.service.Money;
import com.fruver.infrastructure.persistence.OrderRepository;
import com.fruver.infrastructure.persistence.Rows.ItemRow;
import com.fruver.infrastructure.persistence.Rows.OrderRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderViewService {

    private final OrderRepository orders;
    private final QuoteService quotes;
    private final JdbcClient jdbc;
    private final ZoneId zone;

    public OrderViewService(OrderRepository orders, QuoteService quotes, JdbcClient jdbc, AppProperties props) {
        this.orders = orders;
        this.quotes = quotes;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.timezone());
    }

    public List<OrderView> board() {
        OffsetDateTime since = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        return views(orders.listBoard(since));
    }

    public OrderView get(long id) {
        OrderRow row = orders.find(id).orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado"));
        return views(List.of(row)).get(0);
    }

    /** Construye vistas en bloque: 3 consultas auxiliares para N pedidos (sin N+1). */
    List<OrderView> views(List<OrderRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(OrderRow::id).toList();
        Map<Long, List<ItemRow>> itemsByOrder = orders.itemsFor(ids).stream().collect(Collectors.groupingBy(ItemRow::orderId));
        QuoteService.PriceBook book = quotes.priceBook(itemsByOrder.values().stream()
                .flatMap(List::stream).map(ItemRow::productId).toList());
        Map<Long, String> texts = new HashMap<>();
        Map<Long, Integer> msgCounts = new HashMap<>();
        jdbc.sql("""
                SELECT order_id, count(*),
                       (SELECT content FROM conversation_message cm2
                        WHERE cm2.order_id = cm.order_id AND cm2.direction = 'INBOUND'
                        ORDER BY id DESC LIMIT 1) AS last_inbound
                FROM conversation_message cm
                WHERE order_id IN (:ids)
                GROUP BY order_id
                """).param("ids", ids).query((rs, n) -> {
                    long oid = rs.getLong(1);
                    msgCounts.put(oid, rs.getInt(2));
                    texts.put(oid, rs.getString(3));
                    return 0;
                }).list();

        Map<Long, Long> pendingDispatchMap = new HashMap<>();
        List<Long> custIds = rows.stream().map(OrderRow::customerId).filter(Objects::nonNull).distinct().toList();
        if (!custIds.isEmpty()) {
            jdbc.sql("""
                    SELECT customer_id, id FROM orders
                    WHERE customer_id IN (:cids) AND status = 'APPROVED' AND dispatched_at IS NULL
                    ORDER BY id DESC
                    """).param("cids", custIds).query((rs, n) -> {
                        pendingDispatchMap.putIfAbsent(rs.getLong(1), rs.getLong(2));
                        return 0;
                    }).list();
        }

        List<OrderView> out = new ArrayList<>();
        for (OrderRow r : rows) {
            List<ItemRow> items = itemsByOrder.getOrDefault(r.id(), List.of());
            PriceType pt = PriceType.forCustomer(CustomerType.valueOf(r.customerType()));
            Quote quote = quotes.quote(items, pt, book);
            Map<Long, QuoteLine> byItem = quote.lines().stream().collect(Collectors.toMap(QuoteLine::itemId, l -> l));
            List<ItemView> itemViews = items.stream().map(i -> toView(i, byItem.get(i.id()))).toList();
            boolean active = "REVIEW".equals(r.status());
            Long pendingDispatchOrderId = (!"APPROVED".equals(r.status()) && r.customerId() != null)
                    ? pendingDispatchMap.get(r.customerId()) : null;
            out.add(new OrderView(r.id(), r.version(), r.status(), r.conversationStatus(), r.reviewStatus(),
                    r.inputType(), r.aiStatus(), r.aiNote(), r.suggestedReply(),
                    active ? ConfirmationMessageBuilder.build(quote) : null,
                    new OrderView.CustomerInfo(r.customerId(), r.customerName(), Phones.mask(r.customerPhone()), r.customerPhone(), r.customerType()),
                    r.address(), r.notes(), r.receivedAt(), texts.get(r.id()), r.hasAudio(), r.hasImage(), r.corrected(),
                    itemViews, quote.total(), quote.complete(), r.saleId(), r.printStatus(),
                    r.dispatchedAt(), r.dispatchedBy(), msgCounts.getOrDefault(r.id(), 0), pendingDispatchOrderId,
                    r.deliveryPersonId(), r.deliveryPersonName(),
                    r.requiresVerification(), r.verificationReason(),
                    r.targetPrinter(), r.printedPrinter()));
        }
        return out;
    }

    private static ItemView toView(ItemRow i, QuoteLine l) {
        String ai = i.aiProductName() != null || i.aiQuantity() != null
                ? (i.aiProductName() == null ? i.mentioned() : i.aiProductName()) + " "
                  + (i.aiQuantity() == null ? "?" : Money.qty(i.aiQuantity())) + " " + (i.aiUnit() == null ? "?" : i.aiUnit())
                : null;
        String prodName = l != null && l.productName() != null ? l.productName() : (i.aiProductName() != null ? i.aiProductName() : i.mentioned());
        BigDecimal catPrice = l != null ? l.catalogPrice() : null;
        BigDecimal appPrice = l != null ? l.appliedPrice() : i.manualPrice();
        BigDecimal subtotal = l != null ? l.subtotal() : null;
        String problem = l != null ? l.problem() : null;
        return new ItemView(i.id(), i.productId(), prodName, i.mentioned(), i.observation(), i.aiAmbiguous(), ai,
                i.quantity(), i.unit(), catPrice, i.manualPrice(), i.priceChangeReason(),
                appPrice, subtotal, problem);
    }
}
