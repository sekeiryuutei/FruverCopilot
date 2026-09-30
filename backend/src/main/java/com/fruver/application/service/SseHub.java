package com.fruver.application.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Notificaciones servidor -> pantalla. SSE basta: el flujo es unidireccional y reconecta solo. */
@Component
public class SseHub {

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publish(String event, String data) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(event).data(data));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    public void orderChanged(long orderId) {
        publish("orders", String.valueOf(orderId));
    }

    @Scheduled(fixedRate = 25000)
    void heartbeat() {
        publish("ping", "1");
    }
}
