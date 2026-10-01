package com.fruver.interfaces.rest;

import com.fruver.application.service.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Formato de error unico: {timestamp, status, code, message, details}. No expone trazas ni datos internos. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        List<String> d = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).toList();
        return body(400, "VALIDATION_ERROR", "Datos invalidos", d);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return body(400, "BAD_REQUEST", "Solicitud mal formada", List.of());
    }

    /** Antes caia en el manejador generico y respondia 500 (p. ej. GET /favicon.ico). */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return body(404, "NOT_FOUND", "Recurso no encontrado", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return body(405, "METHOD_NOT_ALLOWED", "Metodo no permitido", List.of());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException e) {
        return body(409, "DUPLICATE", "Ya existe un registro con esos datos", List.of());
    }

    /** Red de seguridad: un CHECK/FK de la base (p. ej. cantidad negativa) llega como 422, no como 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        log.warn("Violacion de integridad en base de datos: {}", e.getMostSpecificCause().getMessage());
        return body(422, "VALUE_OUT_OF_RANGE", "Un valor esta fuera del rango permitido", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return body(403, "FORBIDDEN", "No tienes permiso para esta accion", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("Error no controlado", e);
        return body(500, "INTERNAL_ERROR", "Error interno. Si estabas aprobando un pedido, verifica antes de repetir.", List.of());
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message, List<String> details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("status", status);
        m.put("code", code);
        m.put("message", message);
        m.put("details", details);
        return ResponseEntity.status(status).body(m);
    }
}
