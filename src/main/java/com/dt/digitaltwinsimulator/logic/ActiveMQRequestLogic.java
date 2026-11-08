package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.BrokerSettings;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import com.dt.digitaltwinsimulator.config.MessageProperties;
import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestDto;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileAndDataDto;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileDto;
import com.dt.digitaltwinsimulator.entity.dto.DryRunResponseDto;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class ActiveMQRequestLogic {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*(.*?)\\s*\\}\\}");
    private static final Pattern LEGACY_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter LEGACY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter NORMALIZED_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final TaskCancellationLogic taskCancellationLogic;
    private final TaskExecutionStatusLogic taskExecutionStatusLogic;
    private final BrokerConnectionService connections;
    private final MessagePublisher publisher;
    private final SimulatorFileProperties fileProperties;
    private final MessageProperties messageProperties;
    private final MessageBodyLogger messageLogger;
    private final JsonMapper objectMapper;

    public ActiveMQRequestLogic(TaskCancellationLogic taskCancellationLogic, TaskExecutionStatusLogic taskExecutionStatusLogic,
            BrokerConnectionService connections, MessagePublisher publisher, SimulatorFileProperties fileProperties,
            MessageProperties messageProperties, MessageBodyLogger messageLogger, JsonMapper objectMapper) {
        this.taskCancellationLogic = taskCancellationLogic;
        this.taskExecutionStatusLogic = taskExecutionStatusLogic;
        this.connections = connections;
        this.publisher = publisher;
        this.fileProperties = fileProperties;
        this.messageProperties = messageProperties;
        this.messageLogger = messageLogger;
        this.objectMapper = objectMapper;
    }

    public void preflight(ActiveMQRequestDto dto) {
        dryRunTopic(dto, 1);
        connections.verifyConnection(connections.resolve(dto.getActiveMQIp(), dto.getId(), dto.getPw(), dto.getTopic()));
    }

    public void preflight(ActiveMQRequestFileDto dto) throws IOException {
        dryRunFileTopic(dto, 1);
        connections.verifyConnection(connections.resolve(dto.getActiveMQIp(), dto.getId(), dto.getPw(), dto.getTopic()));
    }

    public void preflight(ActiveMQRequestFileAndDataDto dto) throws IOException {
        dryRunFileAndDataTopic(dto, 1);
        connections.verifyConnection(connections.resolve(dto.getActiveMQIp(), dto.getId(), dto.getPw(), dto.getTopic()));
    }

    @Async("threadPoolTaskExecutor")
    public CompletableFuture<String> sendTopic(String taskId, ActiveMQRequestDto dto) {
        BrokerSettings broker = connections.resolve(dto.getActiveMQIp(), dto.getId(), dto.getPw(), dto.getTopic());
        return runTask(taskId, broker, publication -> {
            FormatDefinition definition = FormatDefinition.from(dto.getFormat());
            List<String> rows = sortedValueRows(dto.getValue());
            int repeats = resolveMessageCount(dto.isRepeatBoolean(), dto.getRepeatTime(), dto.getDelayTime(), dto.getMessageCount());
            TpsPacer pacer = new TpsPacer(dto.getTargetTps());
            int sentIndex = 0;
            for (int repeat = 0; repeat < repeats; repeat++) {
                for (int row = 0; row < Math.max(1, rows.size()); row++) {
                    if (isCancelled(taskId)) return;
                    pacer.awaitTurn(sentIndex++);
                    Map<String, Object> payload = rows.isEmpty() ? definition.randomPayload(dto.isTypedValues())
                            : definition.payloadFromValueRow(rows.get(row), dto.isTypedValues());
                    publish(taskId, publication, broker, createStructuredMessage(dto.getTcName(), payload), sentIndex);
                    sleep(dto.getDelayTime());
                }
            }
        });
    }

    public DryRunResponseDto dryRunTopic(ActiveMQRequestDto dto, int limit) {
        FormatDefinition definition = FormatDefinition.from(dto.getFormat());
        List<String> rows = sortedValueRows(dto.getValue());
        int repeats = resolveMessageCount(dto.isRepeatBoolean(), dto.getRepeatTime(), dto.getDelayTime(), dto.getMessageCount());
        int max = sanitizeLimit(limit);
        List<JsonNode> messages = new ArrayList<>();
        for (int repeat = 0; repeat < repeats && messages.size() < max; repeat++) {
            for (int row = 0; row < Math.max(1, rows.size()) && messages.size() < max; row++) {
                var payload = rows.isEmpty() ? definition.randomPayload(dto.isTypedValues())
                        : definition.payloadFromValueRow(rows.get(row), dto.isTypedValues());
                messages.add(toJsonNode(createStructuredMessage(dto.getTcName(), payload)));
            }
        }
        int sourceRows = Math.max(1, rows.size());
        return createDryRunResponse(limit, repeats, sourceRows, (long) repeats * sourceRows,
                rows.isEmpty() ? "RANDOM_FORMAT" : "VALUE_ROWS", messages);
    }

    @Async("threadPoolTaskExecutor")
    public CompletableFuture<String> sendFileTopic(String taskId, ActiveMQRequestFileDto dto) {
        BrokerSettings broker = connections.resolve(dto.getActiveMQIp(), dto.getId(), dto.getPw(), dto.getTopic());
        return runTask(taskId, broker, publication -> {
            String content = Files.readString(resolveFile(dto.getFilePath(), dto.getFileName()), StandardCharsets.UTF_8).trim();
            int repeats = resolveMessageCount(dto.isRepeatBoolean(), dto.getRepeatTime(), dto.getDelayTime(), dto.getMessageCount());
            TpsPacer pacer = new TpsPacer(dto.getTargetTps());
            for (int i = 0; i < repeats; i++) {
                if (isCancelled(taskId)) return;
                pacer.awaitTurn(i);
                publish(taskId, publication, broker, createFileMessage(dto.getTcName(), taskId, content), i + 1L);
                sleep(dto.getDelayTime());
            }
        });
    }

    public DryRunResponseDto dryRunFileTopic(ActiveMQRequestFileDto dto, int limit) throws IOException {
        String content = Files.readString(resolveFile(dto.getFilePath(), dto.getFileName()), StandardCharsets.UTF_8).trim();
        int repeats = resolveMessageCount(dto.isRepeatBoolean(), dto.getRepeatTime(), dto.getDelayTime(), dto.getMessageCount());
        List<JsonNode> messages = new ArrayList<>();
        for (int i = 0; i < repeats && messages.size() < sanitizeLimit(limit); i++) {
            messages.add(toJsonNode(createFileMessage(dto.getTcName(), "dry-run", content)));
        }
        return createDryRunResponse(limit, repeats, 1, repeats, "FILE_REPEAT", messages);
    }

    @Async("threadPoolTaskExecutor")
    public CompletableFuture<String> sendFileAndDataTopic(String taskId, ActiveMQRequestFileAndDataDto dto) {
        BrokerSettings broker = connections.resolve(dto.getActiveMQIp(), dto.getId(), dto.getPw(), dto.getTopic());
        return runTask(taskId, broker, publication -> {
            String template = Files.readString(resolveFile(dto.getFilePath(), dto.getFormatFileName()), StandardCharsets.UTF_8);
            List<String[]> rows = readDataLines(resolveFile(dto.getFilePath(), dto.getDataFileName()), template);
            int repeats = resolveMessageCount(dto.isRepeatBoolean(), dto.getRepeatTime(), dto.getDelayTime(), dto.getMessageCount());
            TpsPacer pacer = new TpsPacer(dto.getTargetTps());
            int sentIndex = 0;
            for (int repeat = 0; repeat < repeats; repeat++) {
                for (String[] row : rows) {
                    if (isCancelled(taskId)) return;
                    pacer.awaitTurn(sentIndex++);
                    publish(taskId, publication, broker, createFileDataMessage(dto.getTcName(), renderTemplate(template, row)), sentIndex);
                    sleep(dto.getDelayTime());
                }
            }
        });
    }

    public DryRunResponseDto dryRunFileAndDataTopic(ActiveMQRequestFileAndDataDto dto, int limit) throws IOException {
        String template = Files.readString(resolveFile(dto.getFilePath(), dto.getFormatFileName()), StandardCharsets.UTF_8);
        List<String[]> rows = readDataLines(resolveFile(dto.getFilePath(), dto.getDataFileName()), template);
        int repeats = resolveMessageCount(dto.isRepeatBoolean(), dto.getRepeatTime(), dto.getDelayTime(), dto.getMessageCount());
        List<JsonNode> messages = new ArrayList<>();
        for (int repeat = 0; repeat < repeats && messages.size() < sanitizeLimit(limit); repeat++) {
            for (String[] row : rows) {
                if (messages.size() >= sanitizeLimit(limit)) break;
                messages.add(toJsonNode(createFileDataMessage(dto.getTcName(), renderTemplate(template, row))));
            }
        }
        return createDryRunResponse(limit, repeats, rows.size(), (long) repeats * rows.size(), "FILE_DATA", messages);
    }

    private void publish(String taskId, MessagePublisher.Publication publication, BrokerSettings broker, String body, long sequence) {
        toJsonNode(body);
        publication.publish(broker.topic(), body, Map.of());
        taskExecutionStatusLogic.incrementSentCount(taskId);
        messageLogger.logMessage(taskId, sequence, body);
    }

    private CompletableFuture<String> runTask(String taskId, BrokerSettings broker, TaskWork work) {
        registerTask(taskId);
        try {
            try (var publication = publisher.open(broker)) {
                work.run(publication);
            }
            boolean cancelled = taskCancellationLogic.isCancellationRequested(taskId);
            if (cancelled) taskExecutionStatusLogic.markCancelled(taskId);
            else taskExecutionStatusLogic.markSuccess(taskId);
            return CompletableFuture.completedFuture(cancelled ? "Cancelled" : "success");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskExecutionStatusLogic.markCancelled(taskId);
            throw new RuntimeException(e);
        } catch (Exception e) {
            taskExecutionStatusLogic.markFailed(taskId, e);
            throw new RuntimeException(e);
        } finally {
            taskCancellationLogic.removeTask(taskId);
        }
    }

    @FunctionalInterface
    private interface TaskWork {
        void run(MessagePublisher.Publication publication) throws Exception;
    }

    private DryRunResponseDto createDryRunResponse(int requestedLimit, int repeatCount, int sourceRowCount, long estimatedTotalMessagesPerTask, String generationMode, List<JsonNode> messages) {
        return new DryRunResponseDto(Math.min(Math.max(requestedLimit, 1), 100), messages.size(), repeatCount, sourceRowCount, estimatedTotalMessagesPerTask, generationMode, messages);
    }

    private JsonNode toJsonNode(String json) {
        try {
            JsonNode node = objectMapper.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Generated message must be a JSON object.");
            return node;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Generated message is not valid JSON.", e);
        }
    }

    private void registerTask(String taskId) {
        taskCancellationLogic.registerTask(taskId);
        taskExecutionStatusLogic.markRunning(taskId);
    }

    private boolean isCancelled(String taskId) {
        if (taskCancellationLogic.isCancellationRequested(taskId)) {
            log.info("작업이 취소되었습니다: {}", taskId);
            return true;
        }
        return false;
    }

    private String createStructuredMessage(String tcName, Map<String, ?> payload) throws JacksonException {
        String nowString = nowTimestamp();
        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put(nowString, payload);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("CREATE_TIMESTAMP", nowString);
        root.put("MESSAGE_ID", tcName);
        root.put("DATA_MAP", dataMap);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private String createFileMessage(String tcName, String taskId, String fileContents) {
        String nowString = nowTimestamp();
        String normalizedFileContents = removeOuterBraces(fileContents);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"CREATE_TIMESTAMP\":\"").append(escapeJsonString(nowString)).append("\",");
        sb.append("\"MESSAGE_ID\":\"").append(escapeJsonString(tcName)).append("\",");
        sb.append("\"DATA_MAP\":{");
        sb.append('\"').append(escapeJsonString(nowString)).append("\":{");
        sb.append("\"USER_ID\":\"").append(escapeJsonString(taskId)).append('\"');
        if (!normalizedFileContents.isBlank()) sb.append(',').append(normalizedFileContents);
        sb.append("}}}");
        return sb.toString();
    }

    private String createFileDataMessage(String tcName, String formatContent) {
        String nowString = nowTimestamp();
        String normalizedFormatContent = removeOuterBraces(formatContent.trim());
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"CREATE_TIMESTAMP\":\"").append(escapeJsonString(nowString)).append("\",");
        sb.append("\"MESSAGE_ID\":\"").append(escapeJsonString(tcName)).append("\",");
        sb.append("\"DATA_MAP\":{");
        sb.append('\"').append(escapeJsonString(nowString)).append("\":{");
        sb.append("\"transaction_code\":\"").append(escapeJsonString(tcName)).append('"');
        for (var field : messageProperties.getFileDataExtraFields().entrySet()) {
            sb.append(',').append('"').append(escapeJsonString(field.getKey())).append("\":\"")
                    .append(escapeJsonString(field.getValue())).append('"');
        }
        if (!normalizedFormatContent.isBlank()) sb.append(',').append(normalizedFormatContent);
        sb.append("}}}");
        return sb.toString();
    }

    private String renderTemplate(String template, String[] data) {
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

    private List<String[]> readDataLines(Path dataFilePath, String template) throws IOException {
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

    private List<String> sortedValueRows(List<Map<Integer, String>> value) {
        if (value == null || value.isEmpty() || value.get(0) == null || value.get(0).isEmpty()) return List.of();
        return value.get(0).entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).filter(Objects::nonNull).filter(row -> !row.isBlank()).collect(Collectors.toList());
    }

    private int resolveMessageCount(boolean repeatBoolean, int repeatTime, int delayTime, int explicitMessageCount) {
        if (explicitMessageCount > 0) return explicitMessageCount;
        if (!repeatBoolean || repeatTime <= 0) return 1;
        int interval = delayTime > 0 ? delayTime : 1000;
        return Math.max(1, (int) Math.ceil((double) repeatTime / interval));
    }

    private int sanitizeLimit(int limit) {
        if (limit <= 0) return 10;
        return Math.min(limit, 100);
    }

    private Path resolveFile(String directory, String filename) {
        try {
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
        } catch (IOException e) {
            throw new IllegalArgumentException("File is missing or unreadable.", e);
        }
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

    private String nowTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    private void sleep(int delayTime) throws InterruptedException {
        if (delayTime > 0) Thread.sleep(delayTime);
    }

    private String removeOuterBraces(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.length() >= 2) return trimmed.substring(1, trimmed.length() - 1).trim();
        return trimmed;
    }

    private String escapeJsonString(String value) {
        String quoted = objectMapper.writeValueAsString(value == null ? "" : value);
        return quoted.substring(1, quoted.length() - 1);
    }

    public String prettyPrintUsingGlobalSetting(String uglyJsonString) throws JacksonException {
        Object jsonObject = objectMapper.readValue(uglyJsonString, Object.class);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
    }

    private static class TpsPacer {
        private final int targetTps;
        private final long startedAt;

        private TpsPacer(int targetTps) {
            this.targetTps = targetTps;
            this.startedAt = System.nanoTime();
        }

        private void awaitTurn(int messageIndex) throws InterruptedException {
            if (targetTps <= 0) return;
            long targetNanos = startedAt + ((long) messageIndex * 1_000_000_000L / targetTps);
            long sleepNanos = targetNanos - System.nanoTime();
            if (sleepNanos <= 0) return;
            Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
        }
    }

    private record FormatDefinition(List<String> dataIds, List<String> dataTypes, List<String> randomBooleans, List<String> randomConditions) {
        private static FormatDefinition from(List<Map<String, String>> format) {
            if (format == null || format.isEmpty() || format.get(0) == null) throw new IllegalArgumentException("format 정보가 필요합니다.");
            Map<String, String> firstFormat = format.get(0);
            List<String> dataIds = splitRequired(firstFormat.get("dataId"), "dataId");
            List<String> dataTypes = splitWithDefault(firstFormat.get("dataType"), dataIds.size(), "String");
            List<String> randomBooleans = splitWithDefault(firstFormat.get("randomBoolean"), dataIds.size(), "0");
            List<String> randomConditions = splitWithDefault(firstFormat.get("randomCondition"), dataIds.size(), "");
            return new FormatDefinition(dataIds, dataTypes, randomBooleans, randomConditions);
        }

        private Map<String, Object> randomPayload(boolean typedValues) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (int i = 0; i < dataIds.size(); i++) payload.put(dataIds.get(i), typedValue(RandomValueGenerator.generate(dataTypes.get(i), randomConditions.get(i)), dataTypes.get(i), typedValues));
            return payload;
        }

        private Map<String, Object> payloadFromValueRow(String row, boolean typedValues) {
            String[] values = row.split(",", -1);
            Map<String, Object> payload = new LinkedHashMap<>();
            for (int i = 0; i < dataIds.size(); i++) {
                if (RandomValueGenerator.isRandomEnabled(randomBooleans.get(i))) {
                    payload.put(dataIds.get(i), typedValue(RandomValueGenerator.generate(dataTypes.get(i), randomConditions.get(i)), dataTypes.get(i), typedValues));
                    continue;
                }
                if (i >= values.length) throw new IllegalArgumentException("value 컬럼 수가 format dataId 수보다 적습니다. row=" + row);
                payload.put(dataIds.get(i), typedValue(values[i].trim(), dataTypes.get(i), typedValues));
            }
            return payload;
        }

        private static Object typedValue(String value, String type, boolean enabled) {
            if (!enabled) return value;
            try {
                return switch (type.trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "integer", "int", "long" -> Long.valueOf(value);
                    case "double", "float", "number" -> {
                        double number = Double.parseDouble(value);
                        if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite number.");
                        yield number;
                    }
                    case "boolean", "bool" -> {
                        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                            throw new IllegalArgumentException("Boolean value must be true or false.");
                        }
                        yield Boolean.valueOf(value);
                    }
                    default -> value;
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Value does not match the declared numeric type.", e);
            }
        }

        private static List<String> splitRequired(String value, String fieldName) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("format." + fieldName + " 값이 필요합니다.");
            return split(value);
        }

        private static List<String> splitWithDefault(String value, int size, String defaultValue) {
            List<String> values = value == null || value.isBlank() ? new ArrayList<>() : split(value);
            while (values.size() < size) values.add(defaultValue);
            if (values.size() > size) return values.subList(0, size);
            return values;
        }

        private static List<String> split(String value) {
            return Stream.of(value.split(",", -1)).map(String::trim).collect(Collectors.toCollection(ArrayList::new));
        }
    }
}
