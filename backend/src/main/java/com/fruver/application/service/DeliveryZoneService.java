package com.fruver.application.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryZoneService {

    public record DeliveryZoneView(
            long id,
            String name,
            boolean covered,
            BigDecimal fee,
            BigDecimal minFree,
            String estimatedTime
    ) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public DeliveryZoneService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<DeliveryZoneView> list() {
        return jdbc.sql("SELECT id, name, covered, fee, min_free, estimated_time FROM delivery_zone ORDER BY covered DESC, name ASC")
                .query((rs, n) -> new DeliveryZoneView(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getBoolean(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5),
                        rs.getString(6)
                )).list();
    }

    @Transactional
    public DeliveryZoneView create(String name, boolean covered, BigDecimal fee, BigDecimal minFree, String estimatedTime, String user) {
        if (name == null || name.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "Nombre de zona requerido");
        BigDecimal cleanFee = fee != null ? fee : BigDecimal.ZERO;
        long id = jdbc.sql("""
                INSERT INTO delivery_zone(name, covered, fee, min_free, estimated_time)
                VALUES (:n, :c, :f, :mf, :et) RETURNING id
                """).param("n", name.strip()).param("c", covered).param("f", cleanFee).param("mf", minFree)
                .param("et", estimatedTime != null ? estimatedTime.strip() : null).query(Long.class).single();

        audit.log(user, "DELIVERY_ZONE_CREATED", null, null, Map.of("zoneId", id, "name", name));
        return new DeliveryZoneView(id, name.strip(), covered, cleanFee, minFree, estimatedTime);
    }

    @Transactional
    public DeliveryZoneView update(long id, String name, boolean covered, BigDecimal fee, BigDecimal minFree, String estimatedTime, String user) {
        if (name == null || name.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "Nombre de zona requerido");
        BigDecimal cleanFee = fee != null ? fee : BigDecimal.ZERO;
        int n = jdbc.sql("""
                UPDATE delivery_zone SET name = :n, covered = :c, fee = :f, min_free = :mf, estimated_time = :et
                WHERE id = :id
                """).param("n", name.strip()).param("c", covered).param("f", cleanFee).param("mf", minFree)
                .param("et", estimatedTime != null ? estimatedTime.strip() : null).param("id", id).update();

        if (n == 0) throw new ApiException(404, "ZONE_NOT_FOUND", "Zona no encontrada");
        audit.log(user, "DELIVERY_ZONE_UPDATED", null, null, Map.of("zoneId", id, "name", name));
        return new DeliveryZoneView(id, name.strip(), covered, cleanFee, minFree, estimatedTime);
    }

    @Transactional
    public void delete(long id, String user) {
        jdbc.sql("DELETE FROM delivery_zone WHERE id = :id").param("id", id).update();
        audit.log(user, "DELIVERY_ZONE_DELETED", null, null, Map.of("zoneId", id));
    }

    /** Formato compacto para inyectar en el contexto del LLM */
    public String formatForPrompt() {
        List<DeliveryZoneView> zones = list();
        if (zones.isEmpty()) return "(No hay zonas configuradas. Consultar con el administrador).";
        StringBuilder sb = new StringBuilder();
        for (DeliveryZoneView z : zones) {
            sb.append("- ").append(z.name()).append(": ");
            if (!z.covered()) {
                sb.append("SIN cobertura de domicilios actualmente.");
            } else {
                sb.append("CON cobertura. ");
                if (z.fee() == null || z.fee().compareTo(BigDecimal.ZERO) == 0) {
                    sb.append("Domicilio GRATIS.");
                } else {
                    sb.append("Tarifa: $").append(z.fee().toPlainString());
                    if (z.minFree() != null && z.minFree().compareTo(BigDecimal.ZERO) > 0) {
                        sb.append(" (GRATIS en compras superiores a $").append(z.minFree().toPlainString()).append(").");
                    } else {
                        sb.append(".");
                    }
                }
                if (z.estimatedTime() != null && !z.estimatedTime().isBlank()) {
                    sb.append(" Tiempo aprox: ").append(z.estimatedTime()).append(".");
                }
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}

