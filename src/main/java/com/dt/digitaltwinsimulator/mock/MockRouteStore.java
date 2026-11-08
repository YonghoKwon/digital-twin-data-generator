package com.dt.digitaltwinsimulator.mock;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
class MockRouteStore {
    private static final Logger log = LoggerFactory.getLogger(MockRouteStore.class);
    private static final Pattern METHOD = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Z-]+");
    private static final Pattern HEADER = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private final MockProperties properties;
    private final JsonMapper mapper;
    private final MockTemplate template;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final Map<String, Registered> routes = new LinkedHashMap<>();
    private Instant lastReloadAt;
    private boolean lastReloadSuccessful;
    private String lastReloadError;

    record Registered(MockRoute route, AtomicLong sequence, List<MockRoute.Response> responses, int captures) { }
    record Match(Registered registered, Map<String, String> variables, long sequence) { }
    record InvalidRow(String id, String reason) { }
    record ImportResult(List<String> imported, List<String> skipped, List<InvalidRow> invalid) { }
    record LoadStatus(int routeCount, Instant lastReloadAt, boolean lastReloadSuccessful, String lastReloadError) { }

    MockRouteStore(MockProperties properties, JsonMapper mapper, MockTemplate template) {
        this.properties = properties;
        this.mapper = mapper;
        this.template = template;
    }

    @PostConstruct
    void initialize() {
        try {
            reload();
        } catch (MockInputException exception) {
            // reload records the failure and logs the filename/reason. Startup remains available for repair.
            routes.clear();
        }
    }

    synchronized LoadStatus status() {
        return new LoadStatus(routes.size(), lastReloadAt, lastReloadSuccessful, lastReloadError);
    }

    synchronized List<MockRoute> list() {
        return routes.values().stream().map(Registered::route).toList();
    }

    synchronized MockRoute get(String id) {
        return required(id).route();
    }

    synchronized MockRoute create(MockRoute route) {
        Registered registered = prepare(route);
        if (routes.containsKey(route.id())) throw new MockInputException("Route id already exists");
        routes.put(route.id(), registered);
        return registered.route();
    }

    synchronized MockRoute update(String id, MockRoute route) {
        required(id);
        if (!id.equals(route.id())) throw new MockInputException("Path id and route id must match");
        Registered registered = prepare(route);
        routes.put(id, registered); // LinkedHashMap keeps the original registration position.
        return registered.route();
    }

    synchronized void delete(String id) {
        required(id);
        routes.remove(id);
    }

    synchronized MockRoute enabled(String id, boolean enabled) {
        Registered old = required(id);
        MockRoute route = old.route().withEnabled(enabled);
        routes.put(id, new Registered(route, old.sequence(), old.responses(), old.captures()));
        return route;
    }

    synchronized MockRoute toggle(String id) {
        return enabled(id, Boolean.FALSE.equals(required(id).route().enabled()));
    }

    synchronized Match match(String method, String path) {
        // Stable sorting retains registration order when all specificity keys tie.
        return routes.values().stream()
                .filter(r -> !Boolean.FALSE.equals(r.route().enabled()) && r.route().method().equals(method))
                .filter(r -> matcher.match(r.route().path(), path))
                .sorted(Comparator.<Registered, Boolean>comparing(r -> matcher.isPattern(r.route().path()))
                        .thenComparing(r -> r.route().path().contains("**"))
                        .thenComparingInt(Registered::captures))
                .findFirst()
                .map(r -> new Match(r, Map.copyOf(matcher.extractUriTemplateVariables(r.route().path(), path)),
                        r.sequence().incrementAndGet()))
                .orElse(null);
    }

    synchronized List<MockRoute> reload() {
        lastReloadAt = Instant.now();
        try {
            Map<String, Registered> loaded = loadRoutes();
            // All-or-nothing: an invalid file leaves the previous routes and sequences intact.
            routes.clear();
            routes.putAll(loaded);
            lastReloadSuccessful = true;
            lastReloadError = null;
            return list();
        } catch (RuntimeException exception) {
            MockInputException failure = exception instanceof MockInputException input ? input
                    : new MockInputException("Cannot load routes-dir: invalid or unreadable route definition");
            lastReloadSuccessful = false;
            lastReloadError = failure.getMessage();
            log.error("Mock route reload failed: {}", lastReloadError);
            throw failure;
        }
    }

    private Map<String, Registered> loadRoutes() {
        Map<String, Registered> loaded = new LinkedHashMap<>();
        Path directory = directory();
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new MockInputException("routes-dir must be a directory");
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().toList()) {
                    loadFile(directory, file, loaded);
                }
            } catch (IOException exception) {
                throw new MockInputException("Cannot list routes-dir: unreadable directory");
            }
        }
        return loaded;
    }

    private void loadFile(Path directory, Path file, Map<String, Registered> loaded) {
        String failure = "Cannot load route file '" + file.getFileName() + "': ";
        try {
            // Do not load a JSON route file through a link outside routes-dir either.
            Path safeFile = bodyPath(directory.relativize(file).toString());
            JsonNode root = mapper.readTree(Files.readString(safeFile));
            List<JsonNode> definitions = new ArrayList<>();
            if (root != null && root.isArray()) root.forEach(definitions::add);
            else definitions.add(root);
            for (JsonNode definition : definitions) {
                Registered route = prepare(mapper.treeToValue(definition, MockRoute.class));
                if (loaded.putIfAbsent(route.route().id(), route) != null) {
                    throw new MockInputException("Duplicate route id in routes-dir");
                }
            }
        } catch (MockInputException exception) {
            String reason = exception.getMessage().startsWith("Invalid template token:")
                    ? "Invalid template token" : exception.getMessage();
            throw new MockInputException(failure + reason);
        } catch (tools.jackson.core.JacksonException exception) {
            // Jackson diagnostics can contain source JSON. Publish a safe reason instead.
            throw new MockInputException(failure + "invalid route JSON");
        } catch (IOException | SecurityException exception) {
            throw new MockInputException(failure + "unreadable UTF-8 route file");
        } catch (IllegalArgumentException exception) {
            throw new MockInputException(failure + "invalid route definition");
        }
    }

    synchronized ImportResult importTable(JsonNode rows, boolean overwrite) {
        if (rows == null || !rows.isArray()) throw new MockInputException("DataTable export must be an array");
        Map<String, Registered> pending = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<InvalidRow> invalid = new ArrayList<>();
        int rowNumber = 0;
        for (JsonNode row : rows) {
            String id = "row-" + ++rowNumber;
            try {
                String name = field(row, "Name");
                if (name.isBlank()) throw new MockInputException("Each DataTable row requires Name");
                id = "dtcore-" + name;
                if (!overwrite && (routes.containsKey(id) || pending.containsKey(id))) {
                    skipped.add(id);
                    continue;
                }
                String method = field(row, "ApiMethod");
                int prefix = method.lastIndexOf("::");
                if (prefix >= 0) method = method.substring(prefix + 2);
                String path = field(row, "ApiUrl").split("\\?", 2)[0];
                if (path.isBlank()) throw new MockInputException("Each DataTable row requires an ApiUrl path");
                if (!path.startsWith("/")) path = "/" + path;
                MockRoute route = new MockRoute(id, method, path, 200,
                        Map.of(), "{}", null, null, null, null, List.of(), false, null, true);
                pending.put(id, prepare(route));
            } catch (MockInputException exception) {
                invalid.add(new InvalidRow(id, exception.getMessage()));
            }
        }
        routes.putAll(pending);
        return new ImportResult(List.copyOf(pending.keySet()), List.copyOf(skipped), List.copyOf(invalid));
    }

    String readBodyForServing(MockRoute.Response response) {
        try {
            return readBody(response);
        } catch (MockInputException exception) {
            throw new MockBodyFileException(exception);
        }
    }

    String readBody(MockRoute.Response response) {
        if (response.bodyFile() == null) return response.body();
        try {
            return Files.readString(bodyPath(response.bodyFile()));
        } catch (IOException | SecurityException exception) {
            throw new MockInputException("bodyFile must be a readable UTF-8 file within routes-dir");
        }
    }

    private Registered required(String id) {
        Registered registered = routes.get(id);
        if (registered == null) throw new MockNotFoundException();
        return registered;
    }

    private Registered prepare(MockRoute route) {
        if (route == null || route.id() == null || !route.id().matches("[A-Za-z0-9_.-]{1,200}")) {
            throw new MockInputException("id must contain 1..200 letters, digits, dot, underscore or hyphen");
        }
        String method = route.method() == null ? "" : route.method().trim().toUpperCase(Locale.ROOT);
        if (!METHOD.matcher(method).matches()) throw new MockInputException("Invalid HTTP method");
        if (method.equals("TRACE")) throw new MockInputException("TRACE routes are not supported");
        String path = route.path();
        if (path == null || !path.startsWith("/") || path.contains("?") || path.contains("#")
                || path.contains("\r") || path.contains("\n")) {
            throw new MockInputException("path must start with / and contain no query or fragment");
        }
        try {
            matcher.match(path, path);
            matcher.extractUriTemplateVariables(path, path);
        } catch (RuntimeException exception) {
            throw new MockInputException("Invalid path pattern");
        }
        MockRoute.Response base = new MockRoute.Response(route.status(), route.headers(), route.body(), route.bodyFile(),
                route.delayMs(), route.delayMinMs(), route.delayMaxMs());
        MockRoute.Response defaultResponse = merge(base, null);
        validateResponse(defaultResponse);
        List<MockRoute.Response> definitions = route.responses() == null ? List.of() : new ArrayList<>(route.responses());
        List<MockRoute.Response> responses = new ArrayList<>();
        for (MockRoute.Response override : definitions) {
            if (override == null) throw new MockInputException("responses cannot contain null");
            MockRoute.Response response = merge(base, override);
            validateResponse(response);
            responses.add(response);
        }
        if (responses.isEmpty()) responses.add(defaultResponse);
        MockRoute.Fault fault = route.fault();
        if (fault != null) {
            double rate = fault.errorRate() == null ? 0 : fault.errorRate();
            if (!Double.isFinite(rate) || rate < 0 || rate > 1) throw new MockInputException("errorRate must be in 0..1");
            int status = fault.errorStatus() == null ? 500 : fault.errorStatus();
            if (status < 400 || status > 599) throw new MockInputException("errorStatus must be in 400..599");
            if (fault.errorBody() != null) template.validate(fault.errorBody());
        }
        // Detach mutable JSON collections before publishing a route to concurrent requests.
        MockRoute copy = new MockRoute(route.id(), method, path, route.status(), copyHeaders(route.headers()),
                route.body(), route.bodyFile(), route.delayMs(), route.delayMinMs(), route.delayMaxMs(),
                definitions.stream().map(r -> new MockRoute.Response(r.status(), copyHeaders(r.headers()), r.body(),
                        r.bodyFile(), r.delayMs(), r.delayMinMs(), r.delayMaxMs())).toList(),
                Boolean.TRUE.equals(route.cycle()), fault, !Boolean.FALSE.equals(route.enabled()));
        String capturePattern = path.contains("**") ? "\\{[^}]+}" : "\\{[^}]+}|\\*";
        int captures = (int) Pattern.compile(capturePattern).matcher(path).results().count();
        return new Registered(copy, new AtomicLong(), List.copyOf(responses), captures);
    }

    private MockRoute.Response merge(MockRoute.Response base, MockRoute.Response override) {
        if (override == null) override = new MockRoute.Response(null, null, null, null, null, null, null);
        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (base.headers() != null) headers.putAll(copyHeaders(base.headers()));
        if (override.headers() != null) headers.putAll(copyHeaders(override.headers()));
        boolean ownBody = override.body() != null || override.bodyFile() != null;
        boolean ownDelay = override.delayMs() != null || override.delayMinMs() != null || override.delayMaxMs() != null;
        String body = ownBody ? override.body() : base.body();
        String bodyFile = ownBody ? override.bodyFile() : base.bodyFile();
        return new MockRoute.Response(override.status() != null ? override.status() : base.status() == null ? 200 : base.status(),
                Map.copyOf(headers), body == null && bodyFile == null ? "{}" : body, bodyFile,
                ownDelay ? override.delayMs() : base.delayMs(),
                ownDelay ? override.delayMinMs() : base.delayMinMs(), ownDelay ? override.delayMaxMs() : base.delayMaxMs());
    }

    private void validateResponse(MockRoute.Response response) {
        if (response.status() < 200 || response.status() > 599) throw new MockInputException("status must be in 200..599");
        copyHeaders(response.headers());
        String contentType = response.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("Content-Type")).map(Map.Entry::getValue).findFirst().orElse(null);
        if (contentType != null) {
            try {
                org.springframework.http.MediaType.parseMediaType(contentType);
            } catch (IllegalArgumentException exception) {
                throw new MockInputException("Invalid Content-Type header");
            }
        }
        if (response.body() != null && response.bodyFile() != null) throw new MockInputException("Use body or bodyFile, not both");
        if (response.delayMs() != null && (response.delayMinMs() != null || response.delayMaxMs() != null)) {
            throw new MockInputException("Use delayMs or delayMinMs/delayMaxMs, not both");
        }
        long min = response.delayMs() == null ? response.delayMinMs() == null ? 0 : response.delayMinMs() : response.delayMs();
        long max = response.delayMs() == null ? response.delayMaxMs() == null ? 0 : response.delayMaxMs() : response.delayMs();
        if ((response.delayMinMs() == null) != (response.delayMaxMs() == null)
                || min < 0 || max < min || max > properties.getMaxDelayMs()) {
            throw new MockInputException("Delay range must be complete and within 0..simulator.mock.max-delay-ms");
        }
        template.validate(readBody(response));
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null) return null;
        for (var header : headers.entrySet()) {
            if (header.getKey() == null || !HEADER.matcher(header.getKey()).matches() || header.getValue() == null
                    || header.getValue().chars().anyMatch(c -> c < 32 && c != 9 || c == 127)) {
                throw new MockInputException("Invalid response header");
            }
            if (List.of("content-length", "transfer-encoding", "connection").contains(header.getKey().toLowerCase(Locale.ROOT))) {
                throw new MockInputException("Transport framing headers are managed by the server");
            }
        }
        return Map.copyOf(headers);
    }

    private Path directory() {
        try {
            return Path.of(properties.getRoutesDir()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new MockInputException("Invalid routes-dir");
        }
    }

    private Path bodyPath(String name) {
        try {
            Path directory = directory();
            Path relative = Path.of(name);
            Path file = directory.resolve(relative).normalize();
            if (relative.isAbsolute() || !file.startsWith(directory) || !Files.isRegularFile(file)
                    || !file.toRealPath().startsWith(directory.toRealPath())) {
                throw new MockInputException("bodyFile must stay within routes-dir and name an existing file");
            }
            return file;
        } catch (IOException | InvalidPathException | SecurityException exception) {
            throw new MockInputException("bodyFile must stay within routes-dir and name an existing file");
        }
    }

    private String field(JsonNode row, String name) {
        if (row == null || !row.isObject()) throw new MockInputException("DataTable rows must be objects");
        for (var property : row.properties()) {
            if (property.getKey().equalsIgnoreCase(name)) return property.getValue().asString("").trim();
        }
        return "";
    }
}
