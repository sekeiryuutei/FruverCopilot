package com.fruver.application.service;

import com.fruver.domain.enums.ReviewStatus;
import com.fruver.domain.enums.UnitOfMeasure;
import com.fruver.domain.service.Money;
import com.fruver.infrastructure.persistence.OrderRepository;
import com.fruver.infrastructure.persistence.Rows.ItemRow;
import com.fruver.infrastructure.persistence.Rows.OrderRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/** Correccion humana de la propuesta de la IA, con bloqueo optimista y auditoria del antes/despues. */
@Service
public class OrderEditService {

    public record ItemEdit(Long id, Long productId, BigDecimal quantity, String unit, BigDecimal manualPrice, String priceChangeReason) {}

    public record OrderEdit(int version, String customerName, String address, String notes, List<ItemEdit> items) {}

    private final JdbcClient jdbc;
    private final OrderRepository orders;
    private final AuditService audit;
    private final AppMetrics metrics;

    public OrderEditService(JdbcClient jdbc, OrderRepository orders, AuditService audit, AppMetrics metrics) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Transactional
    public void update(long orderId, OrderEdit edit, String user) {
        OrderRow order = orders.findForUpdate(orderId)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado"));
        if (!List.of("RECEIVED", "PROCESSING", "REVIEW").contains(order.status())) {
            throw new ApiException(409, "ORDER_NOT_EDITABLE", "El pedido ya no se puede editar");
        }
        if (order.version() != edit.version()) {
            throw new ApiException(409, "ORDER_CONFLICT", "Otro usuario o la IA modificaron este pedido. Recarga para ver los cambios.");
        }
        List<ItemEdit> incoming = edit.items() == null ? List.of() : edit.items();
        if (incoming.size() > 60) throw new ApiException(400, "VALIDATION_ERROR", "Demasiadas lineas");
        Set<String> seenProducts = new HashSet<>();
        for (ItemEdit e : incoming) {
            String unit = e.unit() == null ? "" : UnitOfMeasure.parse(e.unit()).map(Enum::name).orElse(e.unit().trim().toUpperCase());
            if (e.productId() != null && !seenProducts.add(e.productId() + "|" + unit)) {
                throw new ApiException(422, "ITEM_DUPLICATED", "El mismo producto aparece en varias lineas: unifica las cantidades en una sola");
            }
        }

        Map<Long, ItemRow> existing = new HashMap<>();
        orders.items(orderId).forEach(i -> existing.put(i.id(), i));
        List<String> changes = new ArrayList<>();
        boolean corrected = order.corrected();
        int line = 0;

        // 1) Eliminar primero las filas que se quitaron para liberar la restriccion UNIQUE (order_id, product_id, unit)
        Set<Long> incomingIds = incoming.stream().map(ItemEdit::id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (ItemRow prev : existing.values()) {
            if (!incomingIds.contains(prev.id())) {
                jdbc.sql("DELETE FROM order_item WHERE id = :id").param("id", prev.id()).update();
                changes.add("- item " + prev.id() + " " + prev.productId() + " " + prev.quantity() + " " + prev.unit());
                corrected = true;
            }
        }

        // 2) Actualizar existentes o insertar nuevos
        for (ItemEdit e : incoming) {
            validate(e);
            String unit = UnitOfMeasure.parse(e.unit()).orElseThrow().name();
            BigDecimal qty = e.quantity().setScale(3, RoundingMode.HALF_UP);
            BigDecimal price = e.manualPrice() == null ? null : e.manualPrice().setScale(0, Money.ROUNDING);
            String reason = e.priceChangeReason() == null || e.priceChangeReason().isBlank() ? null : clip(e.priceChangeReason(), 200);
            ItemRow prev = e.id() == null ? null : existing.get(e.id());

            if (prev == null) {
                jdbc.sql("""
                        INSERT INTO order_item(order_id, line_no, mentioned, product_id, quantity, unit, manual_price, price_change_reason, edited)
                        VALUES (:o, :ln, (SELECT name FROM product WHERE id = :p), :p, :q, :u, :mp, :r, TRUE)
                        """).param("o", orderId).param("ln", line).param("p", e.productId()).param("q", qty)
                        .param("u", unit).param("mp", price).param("r", reason).update();
                changes.add("+ producto " + e.productId() + " " + Money.qty(qty) + " " + unit);
                corrected = true;
            } else {
                boolean productChanged = !Objects.equals(prev.productId(), e.productId());
                boolean qtyChanged = prev.quantity() == null || prev.quantity().compareTo(qty) != 0;
                boolean unitChanged = !unit.equals(prev.unit());
                boolean priceChanged = !Objects.equals(prev.manualPrice() == null ? null : prev.manualPrice().longValue(),
                        price == null ? null : price.longValue());
                if (productChanged || qtyChanged || unitChanged) {
                    changes.add("item " + prev.id() + ": " + prev.productId() + " " + prev.quantity() + " " + prev.unit()
                            + " -> " + e.productId() + " " + qty + " " + unit);
                    corrected = true;
                }
                if (priceChanged) changes.add("item " + prev.id() + ": precio manual " + prev.manualPrice() + " -> " + price);
                jdbc.sql("""
                        UPDATE order_item SET line_no = :ln, product_id = :p, quantity = :q, unit = :u, manual_price = :mp,
                               price_change_reason = :r, edited = edited OR :ed WHERE id = :id AND order_id = :o
                        """).param("ln", line).param("p", e.productId()).param("q", qty).param("u", unit).param("mp", price)
                        .param("r", reason).param("ed", productChanged || qtyChanged || unitChanged)
                        .param("id", prev.id()).param("o", orderId).update();
            }
            line++;
        }

        // Tras la correccion humana: GREEN solo si todo tiene producto activo, cantidad>0 y unidad
        // igual a la del producto. La completitud final (precio, etc.) la decide SaleService.approve -> 422.
        ReviewStatus review = ReviewStatus.GREEN;
        if (incoming.isEmpty()) {
            review = ReviewStatus.RED;
        } else {
            for (ItemEdit e : incoming) {
                String unit = UnitOfMeasure.parse(e.unit()).orElseThrow().name();
                String productUnit = jdbc.sql("SELECT unit FROM product WHERE id = :p").param("p", e.productId())
                        .query(String.class).optional().orElse(null);
                Boolean active = jdbc.sql("SELECT active FROM product WHERE id = :p").param("p", e.productId())
                        .query(Boolean.class).optional().orElse(null);
                if (active == null || !active || productUnit == null || !unit.equals(productUnit)) {
                    review = ReviewStatus.YELLOW;
                    break;
                }
            }
        }
        jdbc.sql("""
                UPDATE orders SET customer_name = COALESCE(:n, customer_name), address = :a, notes = :no, status = 'REVIEW',
                       review_status = :r, corrected = :c, version = version + 1,
                       conversation_status = CASE WHEN conversation_status = 'WAITING_FOR_CUSTOMER' THEN conversation_status
                                                  WHEN :r = 'GREEN' THEN 'READY_FOR_APPROVAL' ELSE 'READY_FOR_REVIEW' END
                WHERE id = :id
                """).param("n", blankToNull(edit.customerName())).param("a", blankToNull(clip(edit.address(), 300)))
                .param("no", blankToNull(clip(edit.notes(), 500))).param("r", review.name()).param("c", corrected)
                .param("id", orderId).update();

        if (edit.customerName() != null && !edit.customerName().isBlank() && order.customerId() != null) {
            jdbc.sql("UPDATE customer SET name = :n WHERE id = :cid")
                    .param("n", edit.customerName().strip()).param("cid", order.customerId()).update();
        }
        if (edit.address() != null && !edit.address().isBlank() && order.customerId() != null) {
            jdbc.sql("""
                    INSERT INTO customer_address(customer_id, label, address, is_default)
                    SELECT :cid, 'Casa', :addr, TRUE
                    WHERE NOT EXISTS (
                        SELECT 1 FROM customer_address WHERE customer_id = :cid AND lower(trim(address)) = lower(trim(:addr))
                    )
                    """).param("cid", order.customerId()).param("addr", edit.address().strip()).update();
            jdbc.sql("UPDATE customer SET address = :addr WHERE id = :cid")
                    .param("addr", edit.address().strip()).param("cid", order.customerId()).update();
        }

        if (!changes.isEmpty()) {
            audit.log(user, "ORDER_EDITED", orderId, null, Map.of("changes", changes));
            if (!order.corrected() && corrected) metrics.humanCorrection();
        }
    }

    public record ManualOrderRequest(
            Long customerId,
            String customerName,
            String customerPhone,
            String address,
            String notes,
            List<ItemEdit> items
    ) {}

    @Transactional
    public long createManualOrder(ManualOrderRequest req, String user) {
        Long customerId = req.customerId();
        String name = req.customerName() != null && !req.customerName().isBlank() ? req.customerName().strip() : null;
        String phone = req.customerPhone() != null && !req.customerPhone().isBlank() ? req.customerPhone().strip() : null;
        String address = req.address() != null && !req.address().isBlank() ? req.address().strip() : null;
        String notes = req.notes() != null && !req.notes().isBlank() ? req.notes().strip() : null;

        if (customerId != null) {
            var c = jdbc.sql("SELECT name, phone, address FROM customer WHERE id = :id")
                    .param("id", customerId)
                    .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)})
                    .optional().orElse(null);
            if (c != null) {
                if (name == null) name = c[0];
                if (phone == null) phone = c[1];
                if (address == null) address = c[2];
            }
        } else if (phone != null) {
            var c = jdbc.sql("SELECT id, name, address FROM customer WHERE phone = :p")
                    .param("p", phone)
                    .query((rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)})
                    .optional().orElse(null);
            if (c != null) {
                customerId = (Long) c[0];
                if (name == null) name = (String) c[1];
                if (address == null) address = (String) c[2];
            } else {
                customerId = jdbc.sql("INSERT INTO customer(name, phone, address, customer_type) VALUES (:n, :p, :a, 'RETAIL') RETURNING id")
                        .param("n", name != null ? name : "Cliente mostrador").param("p", phone).param("a", address).query(Long.class).single();
                if (address != null) {
                    jdbc.sql("INSERT INTO customer_address(customer_id, label, address, is_default) VALUES (:c, 'Casa', :a, TRUE)")
                            .param("c", customerId).param("a", address).update();
                }
            }
        }

        if (name == null) name = "Cliente mostrador";

        long orderId = jdbc.sql("""
                INSERT INTO orders(customer_id, customer_name, customer_phone, address, notes, input_type, status,
                                   conversation_status, review_status, ai_status, ai_note)
                VALUES (:cid, :cn, :cp, :addr, :notes, 'MANUAL', 'REVIEW', 'READY_FOR_APPROVAL', 'GREEN', 'OK',
                        'Creado en mostrador / llamada por ' || :u)
                RETURNING id
                """).param("cid", customerId).param("cn", name).param("cp", phone).param("addr", address)
                .param("notes", notes).param("u", user).query(Long.class).single();

        if (req.items() != null && !req.items().isEmpty()) {
            int line = 0;
            for (ItemEdit e : req.items()) {
                if (e.productId() == null) continue;
                String unit = UnitOfMeasure.parse(e.unit()).orElse(UnitOfMeasure.LB).name();
                BigDecimal qty = e.quantity() != null ? e.quantity().setScale(3, RoundingMode.HALF_UP) : BigDecimal.ONE;
                BigDecimal price = e.manualPrice() != null ? e.manualPrice().setScale(0, Money.ROUNDING) : null;
                jdbc.sql("""
                        INSERT INTO order_item(order_id, line_no, mentioned, product_id, quantity, unit, manual_price, price_change_reason, edited)
                        VALUES (:o, :ln, (SELECT name FROM product WHERE id = :p), :p, :q, :u, :mp, :r, TRUE)
                        """).param("o", orderId).param("ln", line++).param("p", e.productId()).param("q", qty)
                        .param("u", unit).param("mp", price).param("r", e.priceChangeReason()).update();
            }
        }

        audit.log(user, "ORDER_CREATED_MANUAL", orderId, null, Map.of("customerId", customerId != null ? customerId : -1));
        return orderId;
    }

    @Transactional
    public void cancel(long orderId, int version, String user) {
        OrderRow order = orders.findForUpdate(orderId)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado"));
        if (!List.of("RECEIVED", "PROCESSING", "REVIEW").contains(order.status())) {
            throw new ApiException(409, "ORDER_NOT_EDITABLE", "El pedido ya no se puede cancelar");
        }
        if (order.version() != version) throw new ApiException(409, "ORDER_CONFLICT", "El pedido cambio. Recarga.");
        jdbc.sql("UPDATE orders SET status = 'CANCELLED', version = version + 1 WHERE id = :id").param("id", orderId).update();
        audit.log(user, "ORDER_CANCELLED", orderId, null, Map.of());
    }

    private void validate(ItemEdit e) {
        if (e.productId() == null) throw new ApiException(422, "ITEM_INVALID", "Cada linea necesita un producto del catálogo");
        if (e.quantity() == null || e.quantity().signum() <= 0 || e.quantity().compareTo(new BigDecimal("100000")) > 0) {
            throw new ApiException(422, "ITEM_INVALID", "Cantidad inválida");
        }
        // Rechaza cantidades que al redondear a 3 decimales quedan en 0 (ej. 0.0004): el frontend no debe aceptarlas.
        if (e.quantity().setScale(3, RoundingMode.HALF_UP).signum() <= 0) {
            throw new ApiException(422, "ITEM_INVALID", "Cantidad demasiado pequeña");
        }
        if (UnitOfMeasure.parse(e.unit()).isEmpty()) throw new ApiException(422, "ITEM_INVALID", "Unidad invalida");
        if (e.manualPrice() != null && (e.manualPrice().signum() < 0 || e.manualPrice().compareTo(new BigDecimal("100000000")) > 0)) {
            throw new ApiException(422, "ITEM_INVALID", "Precio manual invalido");
        }
        Boolean active = jdbc.sql("SELECT active FROM product WHERE id = :p").param("p", e.productId()).query(Boolean.class).optional().orElse(null);
        if (active == null) throw new ApiException(422, "ITEM_INVALID", "Producto inexistente: " + e.productId());
        if (!active) throw new ApiException(422, "ITEM_INVALID", "Producto inactivo: " + e.productId());
    }

    private static String clip(String s, int max) { return s == null ? null : (s.length() <= max ? s : s.substring(0, max)); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
