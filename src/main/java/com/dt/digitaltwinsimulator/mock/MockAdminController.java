package com.dt.digitaltwinsimulator.mock;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.List;

@RestController
@RequestMapping("/mock-admin")
@Tag(name = "REST Mock Admin", description = "In-memory REST mock routes, DTCore import and request journal")
public class MockAdminController {
    private final MockRouteStore store;
    private final MockJournal journal;

    MockAdminController(MockRouteStore store, MockJournal journal) {
        this.store = store;
        this.journal = journal;
    }

    @GetMapping("/routes")
    public List<MockRoute> routes() {
        return store.list();
    }

    @GetMapping("/status")
    public MockRouteStore.LoadStatus status() {
        return store.status();
    }

    @GetMapping("/routes/{id}")
    public MockRoute route(@PathVariable String id) {
        return store.get(id);
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public MockRoute create(@RequestBody MockRoute route) {
        return store.create(route);
    }

    @PutMapping("/routes/{id}")
    public MockRoute update(@PathVariable String id, @RequestBody MockRoute route) {
        return store.update(id, route);
    }

    @DeleteMapping("/routes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }

    public record Enabled(Boolean enabled) { }

    @PatchMapping("/routes/{id}/enabled")
    public MockRoute enabled(@PathVariable String id, @RequestBody Enabled body) {
        if (body.enabled() == null) throw new MockInputException("enabled is required");
        return store.enabled(id, body.enabled());
    }

    @PostMapping("/routes/{id}/toggle")
    public MockRoute toggle(@PathVariable String id) {
        return store.toggle(id);
    }

    @PostMapping("/routes/reload")
    public List<MockRoute> reload() {
        return store.reload();
    }

    @GetMapping("/requests")
    public List<MockJournal.Entry> requests(@RequestParam(defaultValue = "100") int limit) {
        return journal.recent(limit);
    }

    @DeleteMapping("/requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearRequests() {
        journal.clear();
    }

    @PostMapping("/import/dtcore-api-table")
    public MockRouteStore.ImportResult importTable(@RequestBody JsonNode rows,
                                                   @RequestParam(defaultValue = "false") boolean overwrite) {
        return store.importTable(rows, overwrite);
    }
}
