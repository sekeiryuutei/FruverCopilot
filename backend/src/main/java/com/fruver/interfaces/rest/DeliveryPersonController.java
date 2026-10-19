package com.fruver.interfaces.rest;

import com.fruver.application.service.Actor;
import com.fruver.application.service.DeliveryPersonService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DeliveryPersonController {

    public record DeliveryPersonBody(@NotBlank String name, String phone, Boolean active) {}

    private final DeliveryPersonService service;

    public DeliveryPersonController(DeliveryPersonService service) {
        this.service = service;
    }

    @GetMapping("/delivery-persons")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<DeliveryPersonService.DeliveryPersonView> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return service.list(includeInactive);
    }

    @PostMapping("/delivery-persons")
    @PreAuthorize("hasRole('ADMIN')")
    public DeliveryPersonService.DeliveryPersonView create(@Valid @RequestBody DeliveryPersonBody b) {
        return service.create(b.name(), b.phone(), Actor.name());
    }

    @PutMapping("/delivery-persons/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeliveryPersonService.DeliveryPersonView update(@PathVariable long id, @Valid @RequestBody DeliveryPersonBody b) {
        return service.update(id, b.name(), b.phone(), b.active(), Actor.name());
    }

    @DeleteMapping("/delivery-persons/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable long id) {
        service.delete(id, Actor.name());
    }

    @GetMapping("/reports/delivery-ranking")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public DeliveryPersonService.DeliveryRankingReport ranking() {
        return service.ranking();
    }
}

