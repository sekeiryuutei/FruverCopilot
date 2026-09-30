package com.fruver.application.service;

import java.util.List;

/** Problema de negocio/validacion (no tecnico). Se traduce a un error HTTP consistente. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<String> details;

    public ApiException(int status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : details;
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<String> details() { return details; }
}
