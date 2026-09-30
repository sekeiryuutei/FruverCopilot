package com.fruver.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Metricas operativas. No registra datos personales. */
@Component
public class AppMetrics {

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void orderReceived(String inputType) { registry.counter("fruver.orders.received", "input", inputType).increment(); }
    public void review(String color) { registry.counter("fruver.orders.review", "status", color).increment(); }
    public void aiError(String kind) { registry.counter("fruver.ai.errors", "kind", kind).increment(); }
    public void humanCorrection() { registry.counter("fruver.orders.corrected").increment(); }
    public void approved() { registry.counter("fruver.orders.approved").increment(); }
    public void print(String result) { registry.counter("fruver.print", "result", result).increment(); }
    public void extractionTime(Duration d) { Timer.builder("fruver.ai.extraction").register(registry).record(d); }
}
