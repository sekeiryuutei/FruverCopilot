package com.fruver.application.service;

import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.ProductLookup;
import com.fruver.domain.enums.UnitOfMeasure;
import com.fruver.domain.service.TextTokens;
import com.fruver.infrastructure.persistence.Rows.ProductRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class ProductCatalogService implements ProductLookup {

    public record ProductView(Long id, String name, String unit, boolean active, String imageUrl, Map<String, BigDecimal> prices) {
        public ProductView(Long id, String name, String unit, boolean active, Map<String, BigDecimal> prices) {
            this(id, name, unit, active, null, prices);
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProductCatalogService.class);

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProductCatalogService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ resolucion (IA -> catalogo)

    @Override
    public Resolution resolve(ItemSugeridoDTO item) {
        String clean = null;
        if (item.productoNormalizado() != null && !item.productoNormalizado().isBlank()) {
            clean = item.productoNormalizado().replaceAll("\\[.*?\\]", "").replaceAll("\\(.*?\\)", "").strip();
            List<Resolution> exact = jdbc.sql("SELECT id, unit FROM product WHERE active AND lower(name) = lower(:n)")
                    .param("n", clean)
                    .query((rs, i) -> new Resolution(rs.getLong(1), 1, rs.getString(2))).list();
            if (exact.size() == 1) return exact.get(0);
        }
        // La IA pudo inventar o no normalizar: se busca por palabras significativas (todas deben aparecer).
        String source = item.productoNormalizado() != null && !item.productoNormalizado().isBlank()
                ? item.productoNormalizado() : item.productoMencionado();
        if (source != null) {
            source = source.replaceAll("\\[.*?\\]", "").replaceAll("\\(.*?\\)", "").strip();
        }
        List<String> tokens = TextTokens.significant(source);
        if (tokens.isEmpty()) return new Resolution(null, 0, null);
        StringBuilder sql = new StringBuilder("SELECT id, unit FROM product WHERE active");
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            sql.append(" AND unaccent(lower(name)) LIKE :t").append(i);
            params.put("t" + i, "%" + tokens.get(i) + "%");
        }
        List<Resolution> ids = jdbc.sql(sql.toString()).params(params)
                .query((rs, i) -> new Resolution(rs.getLong(1), 1, rs.getString(2))).list();
        if (!ids.isEmpty()) return ids.get(0);

        // Si no se encontró producto activo:
        // 1. Verificar si existe inactivo (marcado previamente como "no se maneja")
        String targetName = (clean != null && !clean.isBlank()) ? clean : source;
        if (targetName != null && !targetName.isBlank()) {
            String stripped = targetName.strip();
            var inactive = jdbc.sql("SELECT id FROM product WHERE NOT active AND lower(name) = lower(:n)")
                    .param("n", stripped).query(Long.class).optional();
            if (inactive.isPresent()) {
                // Producto marcado explícitamente como "no se maneja": NO agregar
                return new Resolution(null, 0, null);
            }
            // 2. Auto-registrar la fruta o verdura nueva en el catálogo activo
            if (stripped.length() >= 3 && stripped.length() <= 80 && !stripped.matches(".*\\d.*")) {
                String defaultUnit = "LB";
                if ("UNIDAD".equalsIgnoreCase(item.unidadMedida())) defaultUnit = "UNIDAD";
                else if ("KG".equalsIgnoreCase(item.unidadMedida())) defaultUnit = "KG";
                try {
                    long newId = jdbc.sql("""
                            INSERT INTO product(name, unit, active)
                            VALUES (:n, :u, TRUE)
                            ON CONFLICT (lower(name)) DO UPDATE SET active = product.active
                            RETURNING id
                            """).param("n", stripped).param("u", defaultUnit).query(Long.class).single();
                    return new Resolution(newId, 1, defaultUnit);
                } catch (Exception e) {
                    log.warn("No se pudo auto-registrar producto '{}': {}", stripped, e.getMessage());
                }
            }
        }

        return new Resolution(null, 0, null);
    }

    /** Nombres reales del catalogo relacionados con el mensaje: contexto de solo lectura para el LLM. */
    public List<String> candidates(String text) {
        // En una tienda de fruver el catálogo activo suele tener < 150 productos.
        // Los enviamos todos con su unidad de venta y precio oficial RETAIL para que la IA conozca TODO el inventario disponible
        // y nunca invente precios ni diga que no hay algo que sí existe en la tienda.
        List<String> all = jdbc.sql("""
                SELECT concat(p.name, ' [', p.unit, '] ($', COALESCE(pp.amount::text, '0'), '/', p.unit, ')')
                FROM product p
                LEFT JOIN product_price pp ON p.id = pp.product_id AND pp.price_type = 'RETAIL'
                WHERE p.active ORDER BY p.name
                """).query(String.class).list();
        if (all.size() <= 150) {
            return all;
        }
        List<String> tokens = TextTokens.significant(text);
        if (tokens.isEmpty()) {
            return all.stream().limit(50).toList();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT concat(p.name, ' [', p.unit, '] ($', COALESCE(pp.amount::text, '0'), '/', p.unit, ')')
                FROM product p
                LEFT JOIN product_price pp ON p.id = pp.product_id AND pp.price_type = 'RETAIL'
                WHERE p.active AND (
                """);
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("unaccent(lower(p.name)) LIKE :t").append(i)
               .append(" OR similarity(unaccent(lower(p.name)), :s").append(i).append(") > 0.45");
            params.put("t" + i, "%" + tokens.get(i) + "%");
            params.put("s" + i, tokens.get(i));
        }
        sql.append(") ORDER BY p.name LIMIT 50");
        List<String> matches = jdbc.sql(sql.toString()).params(params).query(String.class).list();
        return matches.isEmpty() ? all.stream().limit(50).toList() : matches;
    }

    // ------------------------------------------------------------------ administracion

    public List<ProductView> list(String q, boolean includeInactive) {
        String term = q == null ? "" : q.strip();
        if (term.length() > 100) term = term.substring(0, 100);
        String esc = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<ProductRow> rows = jdbc.sql("""
                SELECT id, name, unit, active, image_url FROM product
                WHERE (:inactive OR active) AND (:q = '' OR unaccent(lower(name)) LIKE unaccent(lower(:like)))
                ORDER BY name LIMIT 500
                """).param("inactive", includeInactive).param("q", term).param("like", "%" + esc + "%")
                .query(ProductRow.class).list();
        Map<Long, Map<String, BigDecimal>> prices = new HashMap<>();
        if (!rows.isEmpty()) {
            jdbc.sql("SELECT product_id, price_type, amount FROM product_price WHERE product_id IN (:ids)")
                    .param("ids", rows.stream().map(ProductRow::id).toList())
                    .query((rs, n) -> {
                        prices.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getBigDecimal(3));
                        return 0;
                    }).list();
        }
        return rows.stream().map(p -> new ProductView(p.id(), p.name(), p.unit(), p.active(), p.imageUrl(),
                prices.getOrDefault(p.id(), Map.of()))).toList();
    }

    @Transactional
    public long create(String name, String unit) {
        return create(name, unit, null);
    }

    @Transactional
    public long create(String name, String unit, String imageUrl) {
        String u = requireUnit(unit);
        long id = jdbc.sql("INSERT INTO product(name, unit, image_url) VALUES (:n, :u, :img) RETURNING id")
                .param("n", requireName(name)).param("u", u).param("img", blankToNull(imageUrl)).query(Long.class).single();
        audit.log("PRODUCT_CREATED", null, null, Map.of("productId", id, "name", name.strip()));
        return id;
    }

    /** No se elimina fisicamente: se desactiva. Las ventas historicas conservan su snapshot. */
    @Transactional
    public void update(long id, String name, String unit, Boolean active) {
        update(id, name, unit, active, null);
    }

    @Transactional
    public void update(long id, String name, String unit, Boolean active, String imageUrl) {
        if (jdbc.sql("SELECT count(*) FROM product WHERE id = :id").param("id", id).query(Integer.class).single() == 0) {
            throw new ApiException(404, "PRODUCT_NOT_FOUND", "Producto no encontrado");
        }
        if (name != null) jdbc.sql("UPDATE product SET name = :n WHERE id = :id").param("n", requireName(name)).param("id", id).update();
        if (unit != null) jdbc.sql("UPDATE product SET unit = :u WHERE id = :id").param("u", requireUnit(unit)).param("id", id).update();
        if (active != null) jdbc.sql("UPDATE product SET active = :a WHERE id = :id").param("a", active).param("id", id).update();
        if (imageUrl != null) jdbc.sql("UPDATE product SET image_url = :img WHERE id = :id").param("img", blankToNull(imageUrl)).param("id", id).update();
        audit.log("PRODUCT_UPDATED", null, null, Map.of("productId", id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String requireName(String n) {
        if (n == null || n.isBlank() || n.length() > 120) throw new ApiException(400, "VALIDATION_ERROR", "Nombre de producto invalido");
        return n.strip();
    }

    private static String requireUnit(String u) {
        return UnitOfMeasure.parse(u).map(Enum::name)
                .orElseThrow(() -> new ApiException(400, "VALIDATION_ERROR", "Unidad invalida (LB, KG o UNIDAD)"));
    }
}
