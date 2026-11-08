package com.dt.digitaltwinsimulator.scenario;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class ScenarioLibrary {
    private final ScenarioProperties properties;
    private final ScenarioCompiler compiler;
    private final JsonMapper mapper;
    public ScenarioLibrary(ScenarioProperties properties, ScenarioCompiler compiler, JsonMapper mapper) {
        this.properties = properties; this.compiler = compiler; this.mapper = mapper;
    }
    public synchronized List<ScenarioResponses.Summary> list() throws IOException {
        Path directory = directory();
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            List<ScenarioResponses.Summary> result = new ArrayList<>();
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String file = path.getFileName().toString();
                try { result.add(summary(file, read(file))); }
                catch (RuntimeException | IOException e) {
                    result.add(new ScenarioResponses.Summary(file, null, null, null, 0, 0, 0, false, "Invalid or unreadable scenario file."));
                }
            }
            return result;
        }
    }
    public synchronized JsonNode read(String file) throws IOException {
        Path path = path(file);
        if (!Files.exists(path)) throw new ScenarioException(404, "SCENARIO_NOT_FOUND", "Scenario file does not exist.");
        try {
            JsonNode node = mapper.readTree(Files.readString(path));
            ScenarioJson.object(node, "scenario"); return node;
        } catch (tools.jackson.core.JacksonException e) { throw new IllegalArgumentException("Invalid scenario JSON."); }
    }
    public synchronized ScenarioResponses.Summary save(String file, JsonNode root) throws IOException {
        Path target = path(file);
        ScenarioPlan plan = compiler.require(root);
        Files.createDirectories(directory());
        // Check again after directory creation; existing links may never escape the configured root.
        target = path(file);
        Path temporary = Files.createTempFile(directory(), ".scenario-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return summary(file, plan, true, null);
    }
    public synchronized void delete(String file) throws IOException {
        if (!Files.deleteIfExists(path(file))) throw new ScenarioException(404, "SCENARIO_NOT_FOUND", "Scenario file does not exist.");
    }
    private ScenarioResponses.Summary summary(String file, JsonNode root) {
        var compilation = compiler.compile(root);
        return summary(file, compilation.plan(), compilation.validation().valid(),
                compilation.validation().valid() ? null : String.join("; ", compilation.validation().errors()));
    }
    private ScenarioResponses.Summary summary(String file, ScenarioPlan plan, boolean valid, String error) {
        return new ScenarioResponses.Summary(file, plan.scenarioId(), plan.name(), plan.description(), plan.eventCount(), plan.trackCount(), plan.durationMs(), valid, error);
    }
    private Path directory() { return Path.of(properties.getDir()).toAbsolutePath().normalize(); }
    private Path path(String file) throws IOException {
        if (file == null || !file.matches("[^/\\\\:\\x00-\\x1F]+\\.json")) throw new IllegalArgumentException("file must be a plain .json filename.");
        Path base = directory(), path = base.resolve(file).normalize();
        if (!path.getParent().equals(base)) throw new IllegalArgumentException("Scenario path must stay within simulator.scenario.dir.");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(path) || !path.toRealPath().startsWith(base.toRealPath()))) {
            throw new IllegalArgumentException("Scenario path must stay within simulator.scenario.dir.");
        }
        return path;
    }
}
