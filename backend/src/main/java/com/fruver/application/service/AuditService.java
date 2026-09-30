package com.fruver.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void log(String action, Long orderId, Long saleId, Map<String, Object> detail) {
        log(Actor.name(), action, orderId, saleId, detail);
    }

    public void log(String user, String action, Long orderId, Long saleId, Map<String, Object> detail) {
        String json;
        try {
            json = mapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.sql("INSERT INTO audit_log(username, action, order_id, sale_id, detail) VALUES (:u, :a, :o, :s, CAST(:d AS jsonb))")
                .param("u", user).param("a", action).param("o", orderId).param("s", saleId).param("d", json).update();
    }
}
