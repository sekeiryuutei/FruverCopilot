package com.fruver.interfaces.rest;

import com.fruver.infrastructure.whatsapp.strategy.WhatsAppProvider;
import com.fruver.infrastructure.whatsapp.strategy.WhatsAppServiceContext;
import com.fruver.infrastructure.whatsapp.strategy.WhatsAppStatusDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppConfigController {

    private final WhatsAppServiceContext context;

    public WhatsAppConfigController(WhatsAppServiceContext context) {
        this.context = context;
    }

    @GetMapping("/status")
    public ResponseEntity<WhatsAppStatusDto> getStatus() {
        return ResponseEntity.ok(context.getStatus());
    }

    @PostMapping("/provider")
    public ResponseEntity<WhatsAppStatusDto> setProvider(@RequestBody Map<String, String> body) {
        String provStr = body.get("provider");
        WhatsAppProvider prov = WhatsAppProvider.fromString(provStr);
        context.setActiveProvider(prov);
        return ResponseEntity.ok(context.getStatus());
    }

    @PostMapping("/logout")
    public ResponseEntity<WhatsAppStatusDto> logout() {
        context.logout();
        return ResponseEntity.ok(context.getStatus());
    }
}
