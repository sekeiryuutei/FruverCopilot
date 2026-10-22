package com.fruver.application.service;

import com.fruver.domain.enums.CustomerType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CustomerService {

    public record CustomerView(long id, String name, String phone, String phoneMasked, String customerType, String address) {
        public CustomerView(long id, String name, String phoneMasked, String customerType, String address) {
            this(id, name, null, phoneMasked, customerType, address);
        }
    }

    public record CustomerAddressView(long id, long customerId, String label, String address, boolean isDefault) {}

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
                .query((rs, n) -> new CustomerView(rs.getLong(1), rs.getString(2), rs.getString(3), Phones.mask(rs.getString(3)), rs.getString(4), rs.getString(5))).list();
    }

    @Transactional
    public CustomerView createCustomer(String name, String phone, String address, String label, CustomerType type, String user) {
        if (phone == null || phone.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "Teléfono requerido");
        String p = phone.strip();
        if (!p.matches("^[+0-9\\s\\-()]{5,20}$")) throw new ApiException(400, "VALIDATION_ERROR", "Teléfono inválido");
        String cleanName = name != null && !name.isBlank() ? name.strip() : "Cliente";
        String cleanAddr = address != null && !address.isBlank() ? address.strip() : null;
        String cleanLabel = label != null && !label.isBlank() ? label.strip() : "Casa";
        CustomerType cType = type != null ? type : CustomerType.RETAIL;

        var existing = jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", p).query(Long.class).optional();
        if (existing.isPresent()) {
            throw new ApiException(409, "CUSTOMER_ALREADY_EXISTS", "Ya existe un cliente registrado con ese número de teléfono");
        }

        long id = jdbc.sql("""
                INSERT INTO customer(name, phone, customer_type, address)
                VALUES (:n, :p, :t, :a) RETURNING id
                """).param("n", cleanName).param("p", p).param("t", cType.name()).param("a", cleanAddr).query(Long.class).single();

        if (cleanAddr != null) {
            jdbc.sql("""
                    INSERT INTO customer_address(customer_id, label, address, is_default)
                    VALUES (:cid, :lbl, :addr, TRUE)
                    """).param("cid", id).param("lbl", cleanLabel).param("addr", cleanAddr).update();
        }

        audit.log(user, "CUSTOMER_CREATED", null, null, Map.of("customerId", id, "phone", p, "name", cleanName));
        return new CustomerView(id, cleanName, p, Phones.mask(p), cType.name(), cleanAddr);
    }

    @Transactional
    public CustomerView updateCustomer(long id, String name, String phone, CustomerType type, String user) {
        var prev = jdbc.sql("SELECT id, name, phone, customer_type, address FROM customer WHERE id = :id")
                .param("id", id).query((rs, n) -> new Object[]{rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}).optional();
        if (prev.isEmpty()) throw new ApiException(404, "CUSTOMER_NOT_FOUND", "Cliente no encontrado");

        String p = phone != null ? phone.strip() : (String) prev.get()[1];
        String cleanName = name != null ? name.strip() : (String) prev.get()[0];
        CustomerType cType = type != null ? type : CustomerType.valueOf((String) prev.get()[2]);
        String addr = (String) prev.get()[3];

        if (p != null && !p.equals(prev.get()[1])) {
            var conflict = jdbc.sql("SELECT id FROM customer WHERE phone = :p AND id <> :id").param("p", p).param("id", id).query(Long.class).optional();
            if (conflict.isPresent()) throw new ApiException(409, "PHONE_ALREADY_EXISTS", "Ese número de teléfono ya pertenece a otro cliente");
        }

        jdbc.sql("UPDATE customer SET name = :n, phone = :p, customer_type = :t WHERE id = :id")
                .param("n", cleanName).param("p", p).param("t", cType.name()).param("id", id).update();

        // Actualizar nombres en pedidos y ventas
        jdbc.sql("UPDATE orders SET customer_name = :n, customer_phone = :p WHERE customer_id = :id")
                .param("n", cleanName).param("p", p).param("id", id).update();
        jdbc.sql("UPDATE sale SET customer_name = :n, customer_phone = :p WHERE customer_id = :id")
                .param("n", cleanName).param("p", p).param("id", id).update();

        audit.log(user, "CUSTOMER_UPDATED", null, null, Map.of("customerId", id, "name", cleanName, "type", cType.name()));
        return new CustomerView(id, cleanName, p, Phones.mask(p), cType.name(), addr);
    }

    public List<CustomerAddressView> listAddresses(long customerId) {
        return jdbc.sql("SELECT id, customer_id, label, address, is_default FROM customer_address WHERE customer_id = :cid ORDER BY is_default DESC, id DESC")
                .param("cid", customerId)
                .query((rs, n) -> new CustomerAddressView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5))).list();
    }

    @Transactional
    public CustomerAddressView addAddress(long customerId, String label, String address, boolean isDefault, String user) {
        if (address == null || address.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "La dirección no puede estar vacía");
        String cleanAddr = address.strip();
        String cleanLabel = label != null && !label.isBlank() ? label.strip() : "Casa";

        if (isDefault) {
            jdbc.sql("UPDATE customer_address SET is_default = FALSE WHERE customer_id = :cid").param("cid", customerId).update();
            jdbc.sql("UPDATE customer SET address = :a WHERE id = :cid").param("a", cleanAddr).param("cid", customerId).update();
        }

        long addrId = jdbc.sql("""
                INSERT INTO customer_address(customer_id, label, address, is_default)
                VALUES (:cid, :lbl, :addr, :def) RETURNING id
                """).param("cid", customerId).param("lbl", cleanLabel).param("addr", cleanAddr).param("def", isDefault).query(Long.class).single();

        audit.log(user, "CUSTOMER_ADDRESS_ADDED", null, null, Map.of("customerId", customerId, "label", cleanLabel, "address", cleanAddr));
        return new CustomerAddressView(addrId, customerId, cleanLabel, cleanAddr, isDefault);
    }

    @Transactional
    public void deleteAddress(long addressId, String user) {
        jdbc.sql("DELETE FROM customer_address WHERE id = :id").param("id", addressId).update();
        audit.log(user, "CUSTOMER_ADDRESS_DELETED", null, null, Map.of("addressId", addressId));
    }

    /** El tipo de cliente es una regla de negocio: solo un usuario autorizado lo cambia. Nunca el LLM ni el cliente. */
    @Transactional
    public void setType(long id, CustomerType type, String user) {
        int n = jdbc.sql("UPDATE customer SET customer_type = :t WHERE id = :id").param("t", type.name()).param("id", id).update();
        if (n == 0) throw new ApiException(404, "CUSTOMER_NOT_FOUND", "Cliente no encontrado");
        audit.log(user, "CUSTOMER_TYPE_CHANGED", null, null, Map.of("customerId", id, "type", type.name()));
    }
}
