package com.dt.digitaltwinsimulator.scenario;

import com.dt.digitaltwinsimulator.broker.*;
import com.dt.digitaltwinsimulator.logic.MessageBodyLogger;
import com.dt.digitaltwinsimulator.mock.MockRouteControl;
import com.dt.digitaltwinsimulator.realtime.DtMessageFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.*;

import static com.dt.digitaltwinsimulator.scenario.ScenarioJson.*;

@Service
public class ScenarioService {
    private final ScenarioProperties properties;
    private final ScenarioLibrary library;
    private final ScenarioCompiler compiler;
    private final DtMessageFactory factory;
    private final BrokerConnectionService connections;
    private final MessagePublisher publisher;
    private final MockRouteControl mocks;
    private final MessageBodyLogger bodies;
    private final Map<String, ScenarioRun> runs = new LinkedHashMap<>();
    private boolean closed;
    public ScenarioService(ScenarioProperties properties, ScenarioLibrary library, ScenarioCompiler compiler,
                           DtMessageFactory factory, BrokerConnectionService connections, MessagePublisher publisher,
                           MockRouteControl mocks, MessageBodyLogger bodies) {
        this.properties = properties; this.library = library; this.compiler = compiler; this.factory = factory;
        this.connections = connections; this.publisher = publisher; this.mocks = mocks; this.bodies = bodies;
    }
    public ScenarioResponses.Status create(JsonNode request) throws IOException {
        object(request, "request");
        if (has(request, "file") == has(request, "scenario")) throw new IllegalArgumentException("Use exactly one of file or scenario.");
        String id = identifier(text(request, "runId", UUID.randomUUID().toString()), "runId");
        ScenarioPlan plan = compiler.require(has(request, "file") ? library.read(text(request, "file", null)) : request.get("scenario").deepCopy());
        double speed = speed(request.get("speed"), plan.speed());
        boolean loop = bool(request, "loop", plan.loop()), paused = bool(request, "startPaused", false);
        long start = integer(request, "startAtMs", 0);
        if (start < 0 || start > plan.durationMs()) throw new IllegalArgumentException("startAtMs must be in 0..durationMs.");
        synchronized (runs) {
            if (closed) throw new ScenarioException(409, "SHUTTING_DOWN", "Application is shutting down.");
            if (runs.containsKey(id) && !runs.get(id).finished()) throw new ScenarioException(409, "DUPLICATE_RUN_ID", "Run is already active.");
            if (runs.values().stream().filter(r -> !r.finished()).count() >= properties.getMaxRuns()) throw new ScenarioException(429, "RUN_LIMIT_EXCEEDED", "Maximum concurrent scenario runs reached.");
            var run = new ScenarioRun(id, plan, speed, loop, start, paused, factory, connections, publisher, mocks, bodies, this::trim);
            runs.put(id, run); run.start(); return run.snapshot();
        }
    }
    public List<ScenarioResponses.Status> list() { synchronized (runs) { return runs.values().stream().map(ScenarioRun::snapshot).toList(); } }
    public ScenarioResponses.Status get(String id) { return required(id).snapshot(); }
    public ScenarioResponses.Status patch(String id, ScenarioResponses.Patch patch) {
        var run = required(id); run.patch(patch); return run.snapshot();
    }
    public ScenarioResponses.Status stop(String id) { var run = required(id); run.stopRequested(); return run.snapshot(); }
    public List<ScenarioResponses.Status> stopAll() {
        synchronized (runs) { runs.values().forEach(ScenarioRun::stopRequested); return list(); }
    }
    public List<ScenarioResponses.EventLog> events(String id, int limit) { return required(id).events(limit); }
    private ScenarioRun required(String id) {
        synchronized (runs) {
            var run = runs.get(id);
            if (run == null) throw new ScenarioException(404, "RUN_NOT_FOUND", "Scenario run does not exist.");
            return run;
        }
    }
    private void trim() {
        synchronized (runs) {
            var ended = runs.entrySet().stream().filter(e -> e.getValue().finished())
                    .sorted(Comparator.comparingLong(e -> e.getValue().endedNanos())).toList();
            for (int i = 0; i < ended.size() - 50; i++) runs.remove(ended.get(i).getKey(), ended.get(i).getValue());
        }
    }
    @PreDestroy public void shutdown() {
        List<ScenarioRun> copy;
        synchronized (runs) { closed = true; copy = List.copyOf(runs.values()); }
        copy.forEach(ScenarioRun::stop);
        for (var run : copy) try { run.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    }
}
