package com.dt.digitaltwinsimulator.realtime;

import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** S1 file rules adapted without touching the legacy private implementation or its byte contract. */
@Component
public class RealtimeFileSource {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(.*?)\\s*}}");
    private static final Pattern LEGACY_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
    private final SimulatorFileProperties properties;
    private final JsonMapper mapper;

    public RealtimeFileSource(SimulatorFileProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public List<JsonNode> load(RealtimeRequest.Source source) throws IOException {
        if (!"FILE_DATA".equals(source.getType())) throw new IllegalArgumentException("source.type must be FILE_DATA.");
        String template = Files.readString(resolve(source.getFilePath(), source.getFormatFileName()), StandardCharsets.UTF_8);
        List<String> names = new ArrayList<>();
        var placeholders = PLACEHOLDER.matcher(template);
        while (placeholders.find()) {
            String name = placeholders.group(1).trim();
            if (name.isBlank()) throw new IllegalArgumentException("Empty file placeholder.");
            names.add(name);
        }
        if (names.isEmpty()) throw new IllegalArgumentException("Format file has no placeholders.");
        List<String[]> rows;
        try (var lines = Files.lines(resolve(source.getFilePath(), source.getDataFileName()), StandardCharsets.UTF_8)) {
            rows = lines.map(s -> s.startsWith("\ufeff") ? s.substring(1) : s).map(String::trim)
                    .filter(s -> !s.isBlank() && !s.startsWith("#")).map(this::csv).toList();
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Data file has no rows.");
        Map<String, Integer> header = new LinkedHashMap<>();
        boolean hasHeader = Arrays.stream(rows.getFirst()).anyMatch(s -> names.contains(s.trim()));
        if (hasHeader) {
            for (int i = 0; i < rows.getFirst().length; i++) header.put(rows.getFirst()[i].trim(), i);
            for (String name : names) if (!header.containsKey(name)) throw new IllegalArgumentException("Missing file header column: " + name);
        }
        List<JsonNode> payloads = new ArrayList<>();
        for (int r = hasHeader ? 1 : 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String[] mapped = new String[names.size()];
            for (int col = 0; col < names.size(); col++) {
                int index = hasHeader ? header.get(names.get(col)) : col;
                if (index >= row.length) throw new IllegalArgumentException("Too few file columns at row " + r + ".");
                mapped[col] = row[index];
            }
            String rendered = render(template, mapped).trim();
            if (!rendered.startsWith("{")) rendered = "{" + rendered + "}";
            try {
                JsonNode node = mapper.readTree(rendered);
                if (node == null || !node.isObject()) throw new IllegalArgumentException();
                payloads.add(node);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid file JSON at row " + r + ".");
            }
        }
        if (payloads.isEmpty()) throw new IllegalArgumentException("No data rows after file header.");
        return List.copyOf(payloads);
    }

    private Path resolve(String directory, String filename) throws IOException {
        if (directory == null || filename == null || filename.isBlank()) throw new IllegalArgumentException("File path and name are required.");
        Path base = Path.of(properties.getBaseDir()).toAbsolutePath().normalize();
        Path dir = base.resolve(directory).normalize();
        Path target = dir.resolve(filename).normalize();
        if (!dir.startsWith(base) || !target.startsWith(dir)) throw new IllegalArgumentException("File path is outside the allowed base directory.");
        Path real = target.toRealPath();
        if (!real.startsWith(base.toRealPath()) || !Files.isRegularFile(real)) throw new IllegalArgumentException("File path is outside the allowed base directory or is not a regular file.");
        return real;
    }

    private String[] csv(String line) {
        List<String> values = new ArrayList<>();
        var value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { values.add(normalize(value.toString())); value.setLength(0); }
            else value.append(c);
        }
        if (quoted) throw new IllegalArgumentException("CSV quote is not closed.");
        values.add(normalize(value.toString()));
        return values.toArray(String[]::new);
    }

    private String normalize(String value) {
        value = value.trim();
        if (LEGACY_DATE.matcher(value).matches()) return LocalDateTime.parse(value,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        return value;
    }

    private String render(String template, String[] row) {
        var matcher = PLACEHOLDER.matcher(template);
        var result = new StringBuilder();
        int col = 0, previous = 0;
        boolean quoted = false, escaped = false;
        while (matcher.find()) {
            for (int i = previous; i < matcher.start(); i++) {
                char c = template.charAt(i);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = !quoted;
            }
            result.append(template, previous, matcher.start());
            String value = row[col++];
            if (quoted) {
                value = mapper.writeValueAsString(value);
                value = value.substring(1, value.length() - 1);
            }
            result.append(value);
            previous = matcher.end();
        }
        return result.append(template.substring(previous)).toString();
    }
}
