package com.dt.digitaltwinsimulator.scenario;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import com.dt.digitaltwinsimulator.logic.FileDataTemplate;
import com.dt.digitaltwinsimulator.logic.SensitiveLogMasker;
import com.dt.digitaltwinsimulator.mock.MockRouteControl;
import com.dt.digitaltwinsimulator.realtime.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

import static com.dt.digitaltwinsimulator.scenario.ScenarioJson.*;

@Component
public class ScenarioCompiler {
    public record Validation(boolean valid, long eventCount, long durationMs, List<String> topics, List<String> errors) { }
    record Compilation(ScenarioPlan plan, Validation validation) { }
    private final ScenarioProperties properties;
    private final DtMessageFactory factory;
    private final MockRouteControl mocks;
    private final BrokerConnectionService connections;
    private final JsonMapper mapper;
    private final FileDataTemplate files;

    public ScenarioCompiler(ScenarioProperties properties, DtMessageFactory factory, MockRouteControl mocks,
                            BrokerConnectionService connections, JsonMapper mapper, SimulatorFileProperties fileProperties) {
        this.properties = properties;
        this.factory = factory;
        this.mocks = mocks;
        this.connections = connections;
        this.mapper = mapper;
        this.files = new FileDataTemplate(fileProperties, mapper);
    }

    public Validation validate(JsonNode definition) { return compile(definition).validation; }
    ScenarioPlan require(JsonNode definition) {
        Compilation result = compile(definition);
        if (!result.validation.valid) throw new IllegalArgumentException(String.join("; ", result.validation.errors));
        return result.plan;
    }

    private static <T> T collect(List<String> errors, String field, Supplier<T> work, T fallback) {
        try { return work.get(); }
        catch (RuntimeException e) {
            String reason = e instanceof IllegalArgumentException ? e.getMessage() : "Invalid or unreadable definition.";
            errors.add(field + ": " + SensitiveLogMasker.mask(reason));
            return fallback;
        }
    }

    Compilation compile(JsonNode root) {
        List<String> errors = new ArrayList<>();
        if (root == null || !root.isObject()) return new Compilation(null, new Validation(false, 0, 0, List.of(), List.of("scenario must be an object.")));
        String scenarioId = collect(errors, "scenarioId", () -> has(root, "scenarioId") ? identifier(text(root, "scenarioId", null), "scenarioId") : null, null);
        String name = collect(errors, "name", () -> text(root, "name", null), null);
        String description = collect(errors, "description", () -> text(root, "description", null), null);
        double speed = collect(errors, "speed", () -> speed(root.get("speed"), 1), 1d);
        boolean loop = collect(errors, "loop", () -> bool(root, "loop", false), false);
        String disconnect = collect(errors, "onDisconnect", () -> {
            String value = text(root, "onDisconnect", "SKIP");
            if (!Set.of("SKIP", "WAIT").contains(value)) throw new IllegalArgumentException("Use SKIP or WAIT.");
            return value;
        }, "SKIP");
        var broker = collect(errors, "broker", () -> {
            var settings = connections.resolve(text(root, "activeMQIp", null), text(root, "id", null), text(root, "pw", null), null);
            try (var ignored = connections.createConnectionFactory(settings)) { return settings; }
        }, connections.resolve(null, null, null, null));
        JsonNode defaults = root.get("defaults");
        if (has(root, "defaults")) collect(errors, "defaults", () -> {
            object(defaults, "defaults");
            for (String key : List.of("topic", "messageId")) text(defaults, key, null);
            // Partial defaults need not contain a DTCORE messageId: an event can supply it.
            var check = (tools.jackson.databind.node.ObjectNode) defaults.deepCopy();
            if (!has(check, "messageId")) check.put("messageId", "validation");
            publication(check, null, mapper.createObjectNode(), false);
            return true;
        }, false);
        List<ScenarioPlan.Series> sources = new ArrayList<>();
        Set<String> topics = new LinkedHashSet<>();
        JsonNode events = root.get("events");
        if (has(root, "events") && !events.isArray()) errors.add("events must be an array.");
        if (events != null && events.isArray()) for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            String prefix = "events[" + i + "]";
            if (!event.isObject()) { errors.add(prefix + " must be an object."); continue; }
            long at = collect(errors, prefix + ".at", () -> at(event.get("at")), 0L);
            String type = collect(errors, prefix + ".type", () -> {
                String value = text(event, "type", "PUBLISH");
                if (!Set.of("PUBLISH", "MARK", "MOCK_UPDATE", "MOCK_ENABLE", "MOCK_DISABLE").contains(value)) throw new IllegalArgumentException("Unknown event type.");
                return value;
            }, "INVALID");
            long[] repeat = collect(errors, prefix + ".repeat", () -> {
                if (!has(event, "repeat")) return new long[]{1, 0};
                JsonNode r = event.get("repeat"); object(r, "repeat");
                long count = integer(r, "count", 0), every = integer(r, "everyMs", -1);
                if (count < 1 || every < 0) throw new IllegalArgumentException("repeat needs count >= 1 and everyMs >= 0.");
                time(Math.addExact(at, Math.multiplyExact(count - 1, every)));
                return new long[]{count, every};
            }, new long[]{1, 0});
            ScenarioPlan.Publish publication = null;
            String routeId = null, label = null;
            com.dt.digitaltwinsimulator.mock.MockRoute route = null;
            if (type.equals("PUBLISH")) {
                publication = collect(errors, prefix, () -> publication(event, defaults, null, false), null);
                if (publication != null) topics.add(publication.topic());
            } else if (type.equals("MARK")) {
                label = collect(errors, prefix + ".label", () -> {
                    String value = text(event, "label", null);
                    if (value == null) throw new IllegalArgumentException("label is required."); return value;
                }, null);
            } else if (type.startsWith("MOCK_")) {
                routeId = collect(errors, prefix + ".routeId", () -> {
                    String id = text(event, "routeId", null);
                    if (id == null || !id.matches("[A-Za-z0-9_.-]{1,200}")) throw new IllegalArgumentException("Valid routeId is required."); return id;
                }, null);
                if (type.equals("MOCK_UPDATE")) {
                    String id = routeId;
                    route = collect(errors, prefix + ".route", () -> mocks.validate(id, event.get("route")), null);
                }
            }
            sources.add(new ScenarioPlan.Repeat(new ScenarioPlan.Event(at, type, publication, routeId, route, label), repeat[0], repeat[1]));
        }
        JsonNode tracks = root.get("tracks");
        if (has(root, "tracks") && !tracks.isArray()) errors.add("tracks must be an array.");
        int trackCount = 0;
        Set<String> trackIds = new HashSet<>();
        if (tracks != null && tracks.isArray()) for (int i = 0; i < tracks.size(); i++) {
            JsonNode track = tracks.get(i);
            Track source = collect(errors, "tracks[" + i + "]", () -> track(track, defaults), null);
            if (source != null) {
                if (!trackIds.add(source.id)) errors.add("Duplicate trackId.");
                sources.add(source); topics.addAll(source.topics); trackCount++;
            }
        }
        long count = 0, duration = 0;
        for (var source : sources) {
            try { count = Math.addExact(count, source.size()); }
            catch (ArithmeticException e) { errors.add("Total event count exceeds supported range."); }
            if (source.size() > 0) duration = Math.max(duration, source.time(source.size() - 1));
        }
        if (count == 0) errors.add("At least one event or track row is required.");
        var validation = new Validation(errors.isEmpty(), count, duration, List.copyOf(topics), List.copyOf(errors));
        var plan = new ScenarioPlan(scenarioId, name, description, speed, loop, disconnect.equals("WAIT"), broker,
                List.copyOf(sources), count, trackCount, duration, List.copyOf(topics));
        return new Compilation(plan, validation);
    }

    private JsonNode field(JsonNode node, JsonNode defaults, String key) {
        return has(node, key) ? node.get(key) : defaults != null && defaults.isObject() ? defaults.get(key) : null;
    }
    private ScenarioPlan.Publish publication(JsonNode node, JsonNode defaults, JsonNode rowPayload, boolean literal) {
        var merged = mapper.createObjectNode();
        for (String key : List.of("topic", "messageId", "envelope", "headers", "standardHeaders")) {
            JsonNode value = field(node, defaults, key);
            if (value != null && !value.isNull()) merged.set(key, value);
        }
        RealtimeRequest request = new RealtimeRequest();
        String topic = text(merged, "topic", connections.resolve(null, null, null, null).topic());
        if (topic.isBlank()) topic = connections.resolve(null, null, null, null).topic();
        request.setMessageId(text(merged, "messageId", null));
        request.setEnvelope(RealtimeRequest.Envelope.valueOf(text(merged, "envelope", "DTCORE")));
        request.setStandardHeaders(bool(merged, "standardHeaders", false));
        if (has(merged, "headers")) {
            object(merged.get("headers"), "headers");
            Map<String, String> headers = new LinkedHashMap<>();
            for (var item : merged.get("headers").properties()) {
                if (!item.getValue().isString()) throw new IllegalArgumentException("headers values must be strings.");
                headers.put(item.getKey(), item.getValue().asString());
            }
            request.setHeaders(headers);
        }
        var spec = factory.specification(request);
        JsonNode payload = rowPayload == null ? node.get("payload") : rowPayload;
        if (literal) factory.validateLiteralPayload(spec, payload); else factory.validatePayload(spec, payload);
        return new ScenarioPlan.Publish(topic, spec, payload.deepCopy(), literal);
    }

    private record Row(long at, long offset, long order) { }
    private Track track(JsonNode node, JsonNode defaults) {
        object(node, "track");
        String id = identifier(text(node, "trackId", null), "trackId");
        if (!"CSV_TIMELINE".equals(text(node, "type", null))) throw new IllegalArgumentException("track type must be CSV_TIMELINE.");
        long startAt = has(node, "startAt") ? at(node.get("startAt")) : 0;
        String directory = text(node, "filePath", null), filename = text(node, "dataFileName", null);
        String format = text(node, "formatFileName", null);
        String template;
        Path path;
        try {
            path = files.resolveFile(directory, filename);
            template = format == null ? null : files.readFile(files.resolveFile(directory, format));
        } catch (IOException | InvalidPathException e) { throw new IllegalArgumentException("Track files must be readable within simulator.files.base-dir."); }
        String timeColumn = text(node, "timeColumn", null);
        if (timeColumn == null || timeColumn.isBlank()) throw new IllegalArgumentException("timeColumn is required.");
        String timeFormat = text(node, "timeFormat", "OFFSET_MS");
        String topicColumn = text(node, "topicColumn", null), messageIdColumn = text(node, "messageIdColumn", null);
        List<Row> rows = new ArrayList<>();
        Set<String> topics = new LinkedHashSet<>();
        Map<String, Integer> columns = new LinkedHashMap<>();
        var track = new Track(id, directory, filename, node.deepCopy(), defaults == null ? null : defaults.deepCopy(),
                template, columns, rows, topics);
        try (var input = new IndexedLines(path)) {
            long firstTime = 0, order = 0;
            boolean header = true;
            while (true) {
                long offset = input.position;
                String line = input.read();
                if (line == null) break;
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                String[] cells = csv(line);
                if (header) {
                    for (int i = 0; i < cells.length; i++) if (cells[i].isBlank() || columns.putIfAbsent(cells[i], i) != null) throw new IllegalArgumentException("CSV header columns must be nonblank and unique.");
                    requireColumn(columns, timeColumn);
                    if (topicColumn != null) requireColumn(columns, topicColumn);
                    if (messageIdColumn != null) requireColumn(columns, messageIdColumn);
                    if (template != null) for (String placeholder : files.placeholderNames(template)) requireColumn(columns, placeholder);
                    header = false; continue;
                }
                if (rows.size() >= properties.getMaxTrackRows()) throw new IllegalArgumentException("Track exceeds simulator.scenario.max-track-rows.");
                if (cells.length < columns.size()) throw new IllegalArgumentException("CSV row has fewer columns than header.");
                long timestamp = timestamp(cells[columns.get(timeColumn)], timeFormat);
                if (order == 0) firstTime = timestamp;
                long at = time(Math.addExact(startAt, timeFormat.startsWith("OFFSET_") ? timestamp : Math.subtractExact(timestamp, firstTime)));
                var publication = track.publication(cells, topicColumn, messageIdColumn);
                topics.add(publication.topic());
                rows.add(new Row(at, offset, order++));
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("CSV must have a header and at least one data row.");
            rows.sort(Comparator.comparingLong(Row::at).thenComparingLong(Row::order));
            track.size = Files.size(path);
            track.modified = Files.getLastModifiedTime(path).toMillis();
            track.topicColumn = topicColumn; track.messageIdColumn = messageIdColumn;
            return track;
        } catch (IOException e) { throw new IllegalArgumentException("Track CSV is unreadable UTF-8."); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Track time is outside supported range."); }
    }
    private void requireColumn(Map<String, Integer> columns, String name) {
        if (!columns.containsKey(name)) throw new IllegalArgumentException("CSV header is missing column: " + name);
    }
    private String[] csv(String line) {
        try { return files.parseCsvRow(line); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid CSV row quoting or date value."); }
    }
    private static String line(RandomAccessFile input) throws IOException {
        String raw = input.readLine();
        return raw == null ? null : StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw.getBytes(StandardCharsets.ISO_8859_1))).toString();
    }
    /** Buffered scan retains byte offsets without loading CSV bodies into the index. */
    private static final class IndexedLines implements AutoCloseable {
        private final BufferedInputStream input;
        private long position;
        IndexedLines(Path path) throws IOException { input = new BufferedInputStream(Files.newInputStream(path)); }
        String read() throws IOException {
            var bytes = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != -1) {
                position++;
                if (next == '\n') break;
                if (next == '\r') {
                    input.mark(1);
                    if (input.read() == '\n') position++; else input.reset();
                    break;
                }
                bytes.write(next);
            }
            if (next == -1 && bytes.size() == 0) return null;
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }
        public void close() throws IOException { input.close(); }
    }
    private long timestamp(String cell, String format) {
        try {
            if (format.equals("OFFSET_MS")) return time(Long.parseLong(cell));
            if (format.equals("OFFSET_SEC")) return time(new BigDecimal(cell).multiply(BigDecimal.valueOf(1000)).longValueExact());
            // S1 normalizes legacy dates in CSV cells before the timeline parser sees them.
            String effective = format.equals("yyyy-MM-dd HH:mm:ss.SSS") && cell.matches("\\d{17}") ? "yyyyMMddHHmmssSSS" : format;
            var parsed = DateTimeFormatter.ofPattern(effective).parseBest(cell, ZonedDateTime::from, LocalDateTime::from, LocalDate::from);
            if (parsed instanceof ZonedDateTime zoned) return zoned.toInstant().toEpochMilli();
            if (parsed instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC).toEpochMilli();
            return ((LocalDate) parsed).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid timeColumn value or timeFormat."); }
    }

    private final class Track implements ScenarioPlan.Series {
        final String id, directory, filename, template;
        final JsonNode node, defaults;
        final Map<String, Integer> columns;
        final List<Row> rows;
        final Set<String> topics;
        String topicColumn, messageIdColumn;
        long size, modified;
        Track(String id, String directory, String filename, JsonNode node, JsonNode defaults, String template,
              Map<String, Integer> columns, List<Row> rows, Set<String> topics) {
            this.id = id; this.directory = directory; this.filename = filename; this.node = node; this.defaults = defaults;
            this.template = template; this.columns = columns; this.rows = rows; this.topics = topics;
        }
        public long size() { return rows.size(); }
        public long time(long index) { return rows.get(Math.toIntExact(index)).at; }
        public ScenarioPlan.Event event(long index) {
            Row row = rows.get(Math.toIntExact(index));
            try {
                Path safe = files.resolveFile(directory, filename);
                if (Files.size(safe) != size || Files.getLastModifiedTime(safe).toMillis() != modified) throw new IllegalArgumentException("Track changed after validation; start a new run.");
                try (var input = new RandomAccessFile(safe.toFile(), "r")) {
                    input.seek(row.offset);
                    var publication = publication(csv(line(input)), topicColumn, messageIdColumn);
                    return new ScenarioPlan.Event(row.at, "PUBLISH", publication, null, null, null);
                }
            } catch (IOException e) { throw new IllegalArgumentException("Track CSV is no longer readable."); }
        }
        ScenarioPlan.Publish publication(String[] cells, String topicColumn, String messageIdColumn) {
            var config = (tools.jackson.databind.node.ObjectNode) node.deepCopy();
            if (topicColumn != null) config.put("topic", cells[columns.get(topicColumn)]);
            if (messageIdColumn != null) config.put("messageId", cells[columns.get(messageIdColumn)]);
            JsonNode payload;
            if (template == null) {
                var object = mapper.createObjectNode();
                columns.forEach((name, index) -> object.put(name, cells[index]));
                payload = object;
            } else {
                String[] values = files.placeholderNames(template).stream().map(name -> cells[columns.get(name)]).toArray(String[]::new);
                try {
                    String rendered = files.renderTemplate(template, values).trim();
                    if (!rendered.startsWith("{")) rendered = "{" + rendered + "}";
                    payload = mapper.readTree(rendered);
                }
                catch (RuntimeException e) { throw new IllegalArgumentException("CSV row does not render valid FILE_DATA JSON."); }
            }
            return ScenarioCompiler.this.publication(config, defaults, payload, true);
        }
    }
}
