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
        jdbc.sql("""
                SELECT DISTINCT ON (order_id) order_id, content FROM conversation_message
                WHERE order_id IN (:ids) AND direction = 'INBOUND' ORDER BY order_id, id DESC
                """).param("ids", ids).query((rs, n) -> { texts.put(rs.getLong(1), rs.getString(2)); return 0; }).list();

        List<OrderView> out = new ArrayList<>();
        for (OrderRow r : rows) {
            List<ItemRow> items = itemsByOrder.getOrDefault(r.id(), List.of());
            PriceType pt = PriceType.forCustomer(CustomerType.valueOf(r.customerType()));
            Quote quote = quotes.quote(items, pt, book);
            Map<Long, QuoteLine> byItem = quote.lines().stream().collect(Collectors.toMap(QuoteLine::itemId, l -> l));
            List<ItemView> itemViews = items.stream().map(i -> toView(i, byItem.get(i.id()))).toList();
            boolean active = "REVIEW".equals(r.status());
            out.add(new OrderView(r.id(), r.version(), r.status(), r.conversationStatus(), r.reviewStatus(),
                    r.inputType(), r.aiStatus(), r.aiNote(), r.suggestedReply(),
                    active ? ConfirmationMessageBuilder.build(quote) : null,
                    new OrderView.CustomerInfo(r.customerId(), r.customerName(), Phones.mask(r.customerPhone()), r.customerType()),
                    r.address(), r.notes(), r.receivedAt(), texts.get(r.id()), r.hasAudio(), r.corrected(),
                    itemViews, quote.total(), quote.complete(), r.saleId(), r.printStatus()));
        }
        return out;
    }

    private static ItemView toView(ItemRow i, QuoteLine l) {
        String ai = i.aiProductName() != null || i.aiQuantity() != null
                ? (i.aiProductName() == null ? i.mentioned() : i.aiProductName()) + " "
                  + (i.aiQuantity() == null ? "?" : Money.qty(i.aiQuantity())) + " " + (i.aiUnit() == null ? "?" : i.aiUnit())
                : null;
        return new ItemView(i.id(), i.productId(), l.productName(), i.mentioned(), i.observation(), i.aiAmbiguous(), ai,
                i.quantity(), i.unit(), l.catalogPrice(), i.manualPrice(), i.priceChangeReason(),
                l.appliedPrice(), l.subtotal(), l.problem());
    }
}
