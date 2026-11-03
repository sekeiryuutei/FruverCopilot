package com.fruver.interfaces.rest;

import com.fruver.application.service.ChatQueryService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatQueryService chatService;

    public ChatController(ChatQueryService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public List<ChatQueryService.ChatSummary> listChats(@RequestParam(required = false) String q) {
        return chatService.listChats(q);
    }

    @GetMapping("/{phone}/messages")
    public List<ChatQueryService.ChatMessageView> getMessages(@PathVariable String phone) {
        return chatService.getMessages(phone);
    }

    public record ManualMessageRequest(String text, String channel, Long quotedMessageId) {}

    @PostMapping("/{phone}/messages")
    public ChatQueryService.ChatMessageView sendManualMessage(
            @PathVariable String phone,
            @RequestBody ManualMessageRequest req,
            @AuthenticationPrincipal UserDetails user) {
        String username = user != null ? user.getUsername() : "operador";
        return chatService.sendManualMessage(phone, req.text(), username, req.channel(), req.quotedMessageId());
    }

    @GetMapping("/audio/{audioId}")
    public ResponseEntity<ByteArrayResource> getAudio(@PathVariable long audioId) {
        return chatService.getAudio(audioId);
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<ByteArrayResource> getImage(@PathVariable long imageId) {
        return chatService.getImage(imageId);
    }
}

