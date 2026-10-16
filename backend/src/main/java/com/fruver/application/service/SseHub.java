package com.fruver.application.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Notificaciones servidor -> pantalla. SSE basta: el flujo es unidireccional y reconecta solo. */
@Component
public class SseHub {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SseHub.class);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Executor sender;

    public SseHub() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("sse-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        ex.initialize();
        this.sender = ex;
    }

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
        // No bloquea al llamador (hilos ai-/scheduler): cada envio va al pool sse- con timeout implicito.
        // Se itera sobre una copia para no bloquear el set concurrente.
        for (SseEmitter e : new ArrayList<>(emitters)) {
            sender.execute(() -> {
                try {
                    e.send(SseEmitter.event().name(event).data(data));
                } catch (Exception ex) {
                    emitters.remove(e);
                }
            });
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
