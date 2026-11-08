package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Shared S1 file rules. Rendering preserves legacy bytes and validation messages. */
public final class FileDataTemplate {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*(.*?)\\s*\\}\\}");
    private static final Pattern LEGACY_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
    private static final DateTimeFormatter LEGACY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter NORMALIZED_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final SimulatorFileProperties fileProperties;
    private final JsonMapper objectMapper;

    public FileDataTemplate(SimulatorFileProperties fileProperties, JsonMapper objectMapper) {
        this.fileProperties = fileProperties;
        this.objectMapper = objectMapper;
    }

    public String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public String renderTemplate(String template, String[] data) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();
        int dataIndex = 0;
        while (matcher.find()) {
            if (dataIndex >= data.length) throw new IllegalArgumentException("데이터 컬럼 수가 포맷 파일의 placeholder 수보다 적습니다.");
            String value = data[dataIndex++];
            if (insideJsonString(template, matcher.start())) value = escapeJsonString(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public List<String[]> readDataLines(Path dataFilePath, String template) throws IOException {
        List<String[]> rows;
        try (Stream<String> lines = Files.lines(dataFilePath, StandardCharsets.UTF_8)) {
            rows = lines.map(this::stripBom).map(String::trim).filter(line -> !line.isBlank()).filter(line -> !line.startsWith("#")).map(this::splitCsvLine).collect(Collectors.toCollection(ArrayList::new));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("데이터 파일에 전송할 데이터가 없습니다.");
        List<String> placeholderNames = extractPlaceholderNames(template);
        if (hasHeaderRow(rows.get(0), placeholderNames)) return mapRowsByHeader(rows, placeholderNames);
        validateDataRows(rows, placeholderNames.size());
        return rows;
    }

    private List<String> extractPlaceholderNames(String template) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (name.isBlank()) throw new IllegalArgumentException("포맷 파일에 빈 placeholder가 있습니다.");
            names.add(name);
        }
        if (names.isEmpty()) throw new IllegalArgumentException("포맷 파일에 placeholder가 없습니다.");
        return names;
    }

    private boolean hasHeaderRow(String[] firstRow, List<String> placeholderNames) {
        for (String header : firstRow) if (placeholderNames.contains(header.trim())) return true;
        return false;
    }

    private List<String[]> mapRowsByHeader(List<String[]> rows, List<String> placeholderNames) {
        String[] header = rows.get(0);
        Map<String, Integer> indexByHeader = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) indexByHeader.put(header[i].trim(), i);
        for (String placeholderName : placeholderNames) if (!indexByHeader.containsKey(placeholderName)) throw new IllegalArgumentException("데이터 파일 header에 placeholder 컬럼이 없습니다: " + placeholderName);
        List<String[]> mappedRows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            String[] source = rows.get(rowIndex);
            String[] mapped = new String[placeholderNames.size()];
            for (int i = 0; i < placeholderNames.size(); i++) {
                int sourceIndex = indexByHeader.get(placeholderNames.get(i));
                if (sourceIndex >= source.length) throw new IllegalArgumentException("데이터 row의 컬럼 수가 header보다 적습니다. rowIndex=" + rowIndex);
                mapped[i] = source[sourceIndex];
            }
            mappedRows.add(mapped);
        }
        if (mappedRows.isEmpty()) throw new IllegalArgumentException("header 외에 전송할 데이터 row가 없습니다.");
        return mappedRows;
    }

    private void validateDataRows(List<String[]> rows, int placeholderCount) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) if (rows.get(rowIndex).length < placeholderCount) throw new IllegalArgumentException("데이터 row의 컬럼 수가 포맷 placeholder 수보다 적습니다. rowIndex=" + rowIndex);
    }

    private String[] splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(normalizeDataValue(current.toString().trim()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (inQuotes) throw new IllegalArgumentException("CSV 따옴표가 닫히지 않았습니다: " + line);
        values.add(normalizeDataValue(current.toString().trim()));
        return values.toArray(String[]::new);
    }

    private String normalizeDataValue(String value) {
        if (LEGACY_DATE_PATTERN.matcher(value).matches()) return LocalDateTime.parse(value, LEGACY_DATE_FORMATTER).format(NORMALIZED_DATE_FORMATTER);
        return value;
    }

    private String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    public Path resolveFile(String directory, String filename) throws IOException {
        if (directory == null || filename == null) throw new IllegalArgumentException("File path and name are required.");
        Path base = Path.of(fileProperties.getBaseDir()).toAbsolutePath().normalize();
        Path requestedDirectory = base.resolve(directory).normalize();
        Path target = requestedDirectory.resolve(filename).normalize();
        if (!requestedDirectory.startsWith(base) || !target.startsWith(requestedDirectory)) {
            throw new IllegalArgumentException("File path is outside the allowed base directory.");
        }
        Path realBase = base.toRealPath();
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(realBase) || !Files.isRegularFile(realTarget)) {
            throw new IllegalArgumentException("File path is outside the allowed base directory or is not a regular file.");
        }
        return realTarget;
    }

    private boolean insideJsonString(String template, int position) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < position; i++) {
            char c = template.charAt(i);
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') quoted = !quoted;
        }
        return quoted;
    }

    private String escapeJsonString(String value) {
        String quoted = objectMapper.writeValueAsString(value == null ? "" : value);
        return quoted.substring(1, quoted.length() - 1);
    }

}
