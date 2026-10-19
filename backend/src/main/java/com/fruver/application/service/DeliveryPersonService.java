package com.fruver.application.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryPersonService {

    public record DeliveryPersonView(long id, String name, String phone, boolean active, OffsetDateTime createdAt) {}

    public record RankingItem(String name, long totalDeliveries, BigDecimal totalAmount, OffsetDateTime lastDelivery) {}

    public record DeliveryRankingReport(List<RankingItem> today, List<RankingItem> thisWeek, List<RankingItem> thisMonth) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public DeliveryPersonService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<DeliveryPersonView> list(boolean includeInactive) {
        String sql = "SELECT id, name, phone, active, created_at FROM delivery_person WHERE (:inc OR active) ORDER BY name ASC";
        return jdbc.sql(sql).param("inc", includeInactive)
                .query((rs, n) -> new DeliveryPersonView(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getObject(5, OffsetDateTime.class)
                )).list();
    }

    @Transactional
    public DeliveryPersonView create(String name, String phone, String user) {
        if (name == null || name.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "Nombre de repartidor requerido");
        long id = jdbc.sql("""
                INSERT INTO delivery_person(name, phone, active)
                VALUES (:n, :p, TRUE) RETURNING id
                """).param("n", name.strip()).param("p", phone != null && !phone.isBlank() ? phone.strip() : null)
                .query(Long.class).single();

        audit.log(user, "DELIVERY_PERSON_CREATED", null, null, Map.of("id", id, "name", name.strip()));
        return new DeliveryPersonView(id, name.strip(), phone != null ? phone.strip() : null, true, OffsetDateTime.now());
    }

    @Transactional
    public DeliveryPersonView update(long id, String name, String phone, Boolean active, String user) {
        if (name == null || name.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "Nombre de repartidor requerido");
        int n = jdbc.sql("""
                UPDATE delivery_person
                SET name = :n, phone = :p, active = COALESCE(:a, active)
                WHERE id = :id
                """).param("n", name.strip()).param("p", phone != null && !phone.isBlank() ? phone.strip() : null)
                .param("a", active).param("id", id).update();

        if (n == 0) throw new ApiException(404, "NOT_FOUND", "Repartidor no encontrado");
        audit.log(user, "DELIVERY_PERSON_UPDATED", null, null, Map.of("id", id, "name", name.strip()));
        return new DeliveryPersonView(id, name.strip(), phone != null ? phone.strip() : null, active != null ? active : true, OffsetDateTime.now());
    }

    @Transactional
    public void delete(long id, String user) {
        jdbc.sql("UPDATE delivery_person SET active = FALSE WHERE id = :id").param("id", id).update();
        audit.log(user, "DELIVERY_PERSON_DELETED", null, null, Map.of("id", id));
    }

    public DeliveryRankingReport ranking() {
        List<RankingItem> today = queryRanking("s.created_at >= date_trunc('day', now())");
        List<RankingItem> thisWeek = queryRanking("s.created_at >= date_trunc('week', now())");
        List<RankingItem> thisMonth = queryRanking("s.created_at >= date_trunc('month', now())");
        return new DeliveryRankingReport(today, thisWeek, thisMonth);
    }

    private List<RankingItem> queryRanking(String dateCondition) {
        String sql = """
                SELECT COALESCE(s.delivery_person_name, 'Sin asignar') AS rep_name,
                       COUNT(s.id) AS total_deliveries,
                       COALESCE(SUM(s.total), 0) AS total_amount,
                       MAX(s.created_at) AS last_delivery
                FROM sale s
                WHERE s.delivery_person_name IS NOT NULL AND s.delivery_person_name != ''
                  AND """ + dateCondition + """
                GROUP BY COALESCE(s.delivery_person_name, 'Sin asignar')
                ORDER BY total_deliveries DESC, total_amount DESC
                """;

        return jdbc.sql(sql).query((rs, n) -> new RankingItem(
                rs.getString(1),
                rs.getLong(2),
                rs.getBigDecimal(3),
                rs.getObject(4, OffsetDateTime.class)
        )).list();
    }
}

