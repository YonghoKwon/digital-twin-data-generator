package com.dt.digitaltwinsimulator.controller;

import com.dt.digitaltwinsimulator.subscribe.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/dt/subscriptions")
@Tag(name = "Subscriptions")
public class SubscriptionController {
    private final SubscriptionService subscriptions;
    public SubscriptionController(SubscriptionService subscriptions) { this.subscriptions = subscriptions; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody SubscriptionRequest request) {
        var result = subscriptions.create(request);
        return ResponseEntity.created(URI.create("/dt/subscriptions/" + result.get("subscriptionId"))).body(result);
    }

    @GetMapping
    public List<Map<String, Object>> list() { return subscriptions.list(); }

    @GetMapping("/{id}/messages")
    public List<ReceivedMessage> messages(@PathVariable String id, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) Long afterSeq, @RequestParam(required = false) String messageId) {
        return subscriptions.messages(id, limit, afterSeq, messageId);
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) { return subscriptions.stream(id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { subscriptions.delete(id); }

    @DeleteMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String id) { subscriptions.clear(id); }

    @ExceptionHandler(SubscriptionException.class)
    public ResponseEntity<Map<String, String>> subscriptionError(SubscriptionException e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.error(), "message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidQuery(Exception e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "INVALID_REQUEST", "message", "Invalid query parameter."));
    }
}
