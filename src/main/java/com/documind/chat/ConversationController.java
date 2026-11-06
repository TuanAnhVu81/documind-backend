package com.documind.chat;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service = service; }
    public record CreateRequest(String title) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationService.ConversationDto create(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) CreateRequest body) {
        return service.create(UUID.fromString(jwt.getSubject()), body == null ? null : body.title());
    }

    @GetMapping
    public ConversationService.Page list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return service.list(UUID.fromString(jwt.getSubject()), page, size);
    }

    @GetMapping("/{id}/messages")
    public ConversationService.MessagePage messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) Long beforeOrdinal, @RequestParam(defaultValue = "50") int limit,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.messages(UUID.fromString(jwt.getSubject()), id, beforeOrdinal, limit);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(UUID.fromString(jwt.getSubject()), id);
    }
}
