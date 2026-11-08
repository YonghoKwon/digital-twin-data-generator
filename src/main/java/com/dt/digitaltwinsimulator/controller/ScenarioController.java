package com.dt.digitaltwinsimulator.controller;

import com.dt.digitaltwinsimulator.scenario.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dt")
@Tag(name = "Scenarios")
public class ScenarioController {
    private final ScenarioLibrary library;
    private final ScenarioCompiler compiler;
    private final ScenarioService service;
    public ScenarioController(ScenarioLibrary library, ScenarioCompiler compiler, ScenarioService service) {
        this.library = library; this.compiler = compiler; this.service = service;
    }
    @GetMapping("/scenarios") public List<ScenarioResponses.Summary> list() throws IOException { return library.list(); }
    @GetMapping("/scenarios/{file}") public JsonNode read(@PathVariable String file) throws IOException { return library.read(file); }
    @PutMapping("/scenarios/{file}") public ScenarioResponses.Summary save(@PathVariable String file, @RequestBody JsonNode body) throws IOException { return library.save(file, body); }
    @DeleteMapping("/scenarios/{file}") public ResponseEntity<Void> delete(@PathVariable String file) throws IOException { library.delete(file); return ResponseEntity.noContent().build(); }
    @PostMapping("/scenarios/validate") public ScenarioCompiler.Validation validate(@RequestBody JsonNode body) { return compiler.validate(body); }
    @PostMapping("/scenario-runs") public ResponseEntity<ScenarioResponses.Status> create(@RequestBody JsonNode body) throws IOException { return ResponseEntity.status(201).body(service.create(body)); }
    @GetMapping("/scenario-runs") public List<ScenarioResponses.Status> runs() { return service.list(); }
    @GetMapping("/scenario-runs/{runId}") public ScenarioResponses.Status get(@PathVariable String runId) { return service.get(runId); }
    @PatchMapping("/scenario-runs/{runId}") public ScenarioResponses.Status patch(@PathVariable String runId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("patch must be an object.");
        Double speed = null; Boolean paused = null; Long seek = null;
        if (body.has("speed")) {
            if (!body.get("speed").isNumber()) throw new IllegalArgumentException("speed must be numeric.");
            speed = body.get("speed").asDouble();
        }
        if (body.has("paused")) {
            if (!body.get("paused").isBoolean()) throw new IllegalArgumentException("paused must be boolean.");
            paused = body.get("paused").asBoolean();
        }
        if (body.has("seekMs")) {
            if (!body.get("seekMs").isIntegralNumber() || !body.get("seekMs").canConvertToLong()) throw new IllegalArgumentException("seekMs must be integer ms.");
            seek = body.get("seekMs").asLong();
        }
        return service.patch(runId, new ScenarioResponses.Patch(speed, paused, seek));
    }
    @DeleteMapping("/scenario-runs/{runId}") public ScenarioResponses.Status stop(@PathVariable String runId) { return service.stop(runId); }
    @DeleteMapping("/scenario-runs") public List<ScenarioResponses.Status> stopAll() { return service.stopAll(); }
    @GetMapping("/scenario-runs/{runId}/events") public List<ScenarioResponses.EventLog> events(@PathVariable String runId, @RequestParam(defaultValue = "50") int limit) { return service.events(runId, limit); }
    @ExceptionHandler(ScenarioException.class) public ResponseEntity<Map<String, String>> error(ScenarioException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.error(), "message", exception.getMessage()));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidParameter() {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", "Invalid query parameter."));
    }
}
