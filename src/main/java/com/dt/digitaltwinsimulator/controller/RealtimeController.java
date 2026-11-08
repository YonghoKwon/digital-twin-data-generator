package com.dt.digitaltwinsimulator.controller;

import com.dt.digitaltwinsimulator.realtime.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dt")
@Tag(name = "Realtime")
public class RealtimeController {
    private final RealtimeService service;
    public RealtimeController(RealtimeService service) { this.service = service; }

    @PostMapping("/publish")
    public RealtimeResponses.Publish publish(@RequestBody RealtimeRequest request) { return service.publish(request); }

    @PostMapping("/publish/preview")
    public RealtimeResponses.Preview preview(@RequestBody RealtimeRequest request) { return service.preview(request); }

    @PostMapping("/streams")
    public ResponseEntity<RealtimeResponses.Stream> create(@RequestBody RealtimeRequest request) throws IOException {
        return ResponseEntity.status(201).body(service.create(request));
    }

    @GetMapping("/streams")
    public List<RealtimeResponses.Stream> list() { return service.list(); }

    @GetMapping("/streams/{id}")
    public RealtimeResponses.Stream get(@PathVariable String id) { return service.get(id); }

    @PatchMapping("/streams/{id}")
    public RealtimeResponses.Stream patch(@PathVariable String id, @RequestBody RealtimeResponses.Patch patch) { return service.patch(id, patch); }

    @DeleteMapping("/streams/{id}")
    public RealtimeResponses.Stream stop(@PathVariable String id) { return service.stop(id); }

    @DeleteMapping("/streams")
    public List<RealtimeResponses.Stream> stopAll() { return service.stopAll(); }

    @ExceptionHandler(RealtimeHttpException.class)
    public ResponseEntity<Map<String, String>> streamError(RealtimeHttpException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.error(), "message", e.getMessage()));
    }
}
