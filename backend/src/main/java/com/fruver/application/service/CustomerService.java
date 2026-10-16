package com.fruver.application.service;

import com.fruver.domain.enums.CustomerType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CustomerService {

    public record CustomerView(long id, String name, String phoneMasked, String customerType, String address) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CustomerService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<CustomerView> search(String q) {
        String term = q == null ? "" : q.strip();
        if (term.length() > 100) term = term.substring(0, 100);
        String esc = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String digits = term.replaceAll("\\D", "");
        if (!digits.isEmpty() && digits.length() < 4 && term.matches("[+\\d\\s\\-()]+")) {
            throw new ApiException(400, "VALIDATION_ERROR", "Para buscar por telefono escribe al menos 4 digitos");
        }
        return jdbc.sql("SELECT id, name, phone, customer_type, address FROM customer WHERE :q = '' OR name ILIKE :like ESCAPE '\\' OR phone LIKE :like ESCAPE '\\' ORDER BY id DESC LIMIT 100")
                .param("q", term).param("like", "%" + esc + "%")
                .query((rs, n) -> new CustomerView(rs.getLong(1), rs.getString(2), Phones.mask(rs.getString(3)), rs.getString(4), rs.getString(5))).list();
    }

    /** El tipo de cliente es una regla de negocio: solo un usuario autorizado lo cambia. Nunca el LLM ni el cliente. */
    @Transactional
    public void setType(long id, CustomerType type, String user) {
        int n = jdbc.sql("UPDATE customer SET customer_type = :t WHERE id = :id").param("t", type.name()).param("id", id).update();
        if (n == 0) throw new ApiException(404, "CUSTOMER_NOT_FOUND", "Cliente no encontrado");
        audit.log(user, "CUSTOMER_TYPE_CHANGED", null, null, Map.of("customerId", id, "type", type.name()));
    }
}
