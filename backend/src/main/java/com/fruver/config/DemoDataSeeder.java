package com.fruver.config;

import com.fruver.application.service.PriceService;
import com.fruver.application.service.ProductCatalogService;
import com.fruver.application.service.SaleService;
import com.fruver.application.service.SseHub;
import com.fruver.domain.enums.PriceType;
import com.fruver.infrastructure.persistence.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datos de DEMOSTRACION (solo con SEED_DEMO_DATA=true; prohibido en produccion).
 * Los pedidos finalizados se aprueban con el mismo SaleService.approve que usa la pantalla, asi que el snapshot,
 * los totales y la auditoria son reales. Los clientes usan telefonos ficticios (5700000001xx) que no existen en WhatsApp.
 */
@Service
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record Product(String name, String unit, int retail) {}

    private record Line(String mentioned, String product, String qty, String unit) {}

    private record DemoOrder(long id, String customer, String phone, String address, String notes, String inputType,
                             String message, int minutesAgo, boolean approve, List<Line> lines) {}

    private static final List<Product> PRODUCTS = List.of(
            new Product("Papa criolla", "LB", 3000),
            new Product("Aguacate Hass", "UNIDAD", 4500),
            new Product("Tomate chonto", "LB", 2500),
            new Product("Cebolla cabezona", "LB", 1800),
            new Product("Platano maduro", "UNIDAD", 1500),
            new Product("Naranja malla", "UNIDAD", 8000)); // "malla" no es unidad del sistema: se vende como 1 UNIDAD = 1 malla

    private static final List<DemoOrder> ORDERS = List.of(
            new DemoOrder(10003, "Andrés Mendoza", "570000000103", "Calle 5 # 38-20, Barrio San Fernando", null, "TEXT",
                    "Buenas, me manda 3 libras de papa criolla y 2 de tomate porfa", 55, true,
                    List.of(new Line("papa criolla", "Papa criolla", "3", "LB"), new Line("tomate", "Tomate chonto", "2", "LB"))),
            new DemoOrder(10004, "Maria Camila Silva", "570000000104", "Carrera 24 # 9-15 apto 402", "Llamar al llegar", "AUDIO",
                    "una malla de naranjas y tres libras de cebolla cabezona, gracias", 35, true,
                    List.of(new Line("malla de naranjas", "Naranja malla", "1", "UNIDAD"), new Line("cebolla", "Cebolla cabezona", "3", "LB"))),
            new DemoOrder(10005, "Felipe Restrepo", "570000000105", "Avenida 3N # 45-60, Granada", "Sin bolsa plástica si se puede", "TEXT",
                    "Hola, necesito 2 aguacates y 4 libras de papa criolla para hoy", 12, false,
                    List.of(new Line("aguacates", "Aguacate Hass", "2", "UNIDAD"), new Line("papa criolla", "Papa criolla", "4", "LB"))));

    private static final List<Long> IDS = ORDERS.stream().map(DemoOrder::id).toList();

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ProductCatalogService catalog;
    private final PriceService prices;
    private final SaleService sales;
    private final OrderRepository orders;
    private final SseHub hub;
    private final AppProperties props;

    public DemoDataSeeder(JdbcClient jdbc, TransactionTemplate tx, ProductCatalogService catalog, PriceService prices,
                          SaleService sales, OrderRepository orders, SseHub hub, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.catalog = catalog;
        this.prices = prices;
        this.sales = sales;
        this.orders = orders;
        this.hub = hub;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed(props.demoSeed().reset());
        } catch (RuntimeException e) {
            log.error("No se pudieron cargar los datos demo (la aplicacion sigue funcionando)", e);
        }
    }

    /** @param reset true = borra y recrea los pedidos demo 10003-10005 (util para repetir la demostracion). */
    public synchronized void seed(boolean reset) {
        List<Long> existing = jdbc.sql("SELECT id FROM orders WHERE id IN (:ids)").param("ids", IDS).query(Long.class).list();
        if (!existing.isEmpty()) {
            long foreign = jdbc.sql("SELECT count(*) FROM orders WHERE id IN (:ids) AND (customer_phone IS NULL OR customer_phone NOT LIKE '5700000001%')")
                    .param("ids", IDS).query(Long.class).single();
            if (foreign > 0) {
                log.warn("Los pedidos 10003-10005 ya existen y NO son de demo: no se toca nada.");
                return;
            }
            if (!reset) {
                log.info("Datos demo ya cargados (usa SEED_DEMO_RESET=true o POST /api/dev/demo/reset para recrearlos).");
                return;
            }
        }
        tx.executeWithoutResult(status -> {
            if (!existing.isEmpty()) deleteDemoOrders();
            Map<String, Long> productIds = ensureProducts();
            for (DemoOrder o : ORDERS) createOrder(o, productIds);
            // La secuencia debe quedar por encima de los ids explicitos, o el proximo pedido real chocaria.
            jdbc.sql("""
                    SELECT setval('order_number_seq', GREATEST((SELECT COALESCE(max(id), 0) FROM orders), 10005,
                                                              (SELECT last_value FROM order_number_seq)))
                    """).query(Long.class).single();
        });
        IDS.forEach(hub::orderChanged);
        log.info("Datos demo cargados: {} productos y pedidos {}", PRODUCTS.size(), IDS);
    }

    private Map<String, Long> ensureProducts() {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Product p : PRODUCTS) {
            Long id = jdbc.sql("SELECT id FROM product WHERE lower(name) = lower(:n)").param("n", p.name()).query(Long.class).optional().orElse(null);
            if (id == null) id = catalog.create(p.name(), p.unit());
            else jdbc.sql("UPDATE product SET active = TRUE WHERE id = :id").param("id", id).update();
            BigDecimal retail = BigDecimal.valueOf(p.retail());
            prices.setPrice(id, PriceType.RETAIL, retail, "demo");
            prices.setPrice(id, PriceType.WHOLESALE, retail.multiply(new BigDecimal("0.87")).setScale(-1, RoundingMode.HALF_UP), "demo");
            ids.put(p.name(), id);
        }
        return ids;
    }

    private void createOrder(DemoOrder o, Map<String, Long> productIds) {
        long customerId = jdbc.sql("""
                INSERT INTO customer(name, phone, address) VALUES (:n, :p, :a)
                ON CONFLICT (phone) DO UPDATE SET name = EXCLUDED.name, address = EXCLUDED.address RETURNING id
                """).param("n", o.customer()).param("p", o.phone()).param("a", o.address()).query(Long.class).single();

        jdbc.sql("""
                INSERT INTO orders(id, customer_id, customer_name, customer_phone, address, notes, input_type, status,
                                   conversation_status, review_status, ai_status, received_at, extracted_at, version)
                VALUES (:id, :c, :n, :p, :a, :no, :it, 'REVIEW', 'READY_FOR_APPROVAL', 'GREEN', 'OK',
                        now() - make_interval(mins => :m), now() - make_interval(mins => :m) + interval '8 seconds', 1)
                """).param("id", o.id()).param("c", customerId).param("n", o.customer()).param("p", o.phone())
                .param("a", o.address()).param("no", o.notes()).param("it", o.inputType()).param("m", o.minutesAgo()).update();

        jdbc.sql("""
                INSERT INTO conversation_message(order_id, direction, message_type, content, external_message_id)
                VALUES (:o, 'INBOUND', :mt, :c, :e)
                """).param("o", o.id()).param("mt", o.inputType()).param("c", o.message()).param("e", "demo-" + o.id()).update();

        int line = 0;
        for (Line l : o.lines()) {
            BigDecimal qty = new BigDecimal(l.qty());
            jdbc.sql("""
                    INSERT INTO order_item(order_id, line_no, mentioned, ai_product_name, ai_quantity, ai_unit, ai_ambiguous,
                                           product_id, quantity, unit)
                    VALUES (:o, :ln, :m, :apn, :q, :u, FALSE, :pid, :q, :u)
                    """).param("o", o.id()).param("ln", line++).param("m", l.mentioned()).param("apn", l.product())
                    .param("q", qty).param("u", l.unit()).param("pid", productIds.get(l.product())).update();
        }

        if (!o.approve()) return;
        sales.approve(o.id(), orders.find(o.id()).orElseThrow().version(), "demo");
        // Historial creible: aprobado 3 min despues de recibido y ya impreso (no se reimprime en la impresora real).
        jdbc.sql("""
                UPDATE orders SET approved_at = now() - make_interval(mins => :m) + interval '3 minutes',
                                  received_at = now() - make_interval(mins => :m),
                                  extracted_at = now() - make_interval(mins => :m) + interval '8 seconds'
                WHERE id = :id
                """).param("m", o.minutesAgo()).param("id", o.id()).update();
        jdbc.sql("""
                UPDATE sale SET created_at = o.approved_at, print_status = 'PRINTED', printed_at = o.approved_at + interval '4 seconds'
                FROM orders o WHERE sale.order_id = o.id AND o.id = :id
                """).param("id", o.id()).update();
        jdbc.sql("UPDATE print_job SET status = 'DONE', updated_at = now() WHERE sale_id = (SELECT id FROM sale WHERE order_id = :id)")
                .param("id", o.id()).update();
    }

    private void deleteDemoOrders() {
        jdbc.sql("DELETE FROM print_job WHERE sale_id IN (SELECT id FROM sale WHERE order_id IN (:ids))").param("ids", IDS).update();
        jdbc.sql("DELETE FROM sale_item WHERE sale_id IN (SELECT id FROM sale WHERE order_id IN (:ids))").param("ids", IDS).update();
        jdbc.sql("DELETE FROM sale WHERE order_id IN (:ids)").param("ids", IDS).update();
        jdbc.sql("DELETE FROM audit_log WHERE order_id IN (:ids)").param("ids", IDS).update();
        jdbc.sql("DELETE FROM orders WHERE id IN (:ids)").param("ids", IDS).update(); // items y mensajes caen en cascada
    }
}
