package com.fruver.interfaces.rest;

import com.fruver.application.dto.BlockingAlert;
import com.fruver.application.service.Actor;
import com.fruver.application.service.BlockingAlertService;
import com.fruver.application.service.SseHub;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class StreamController {

    private final SseHub hub;
    private final BlockingAlertService alertService;

    public StreamController(SseHub hub) {
        this(hub, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StreamController(SseHub hub, @org.springframework.beans.factory.annotation.Autowired(required = false) BlockingAlertService alertService) {
        this.hub = hub;
        this.alertService = alertService;
    }

    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return Map.of("username", Actor.name(), "role", admin ? "ADMIN" : "OPERADOR");
    }

    @GetMapping("/api/v1/system/alerts")
    public List<BlockingAlert> getActiveAlerts() {
        return alertService != null ? alertService.getActiveAlerts() : List.of();
    }

    @PostMapping("/api/v1/system/alerts/{id}/dismiss")
    public Map<String, Object> dismissAlert(@PathVariable("id") String id) {
        boolean ok = alertService != null && alertService.dismiss(id);
        return Map.of("success", ok, "id", id);
    }
}
