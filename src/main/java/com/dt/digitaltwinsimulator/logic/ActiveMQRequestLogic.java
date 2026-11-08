package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestDto;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileAndDataDto;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileDto;
import com.dt.digitaltwinsimulator.entity.dto.DryRunResponseDto;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final ActiveMqBrokerProperties brokerProperties;
    private final JsonMapper objectMapper;

    public ActiveMQRequestLogic(TaskCancellationLogic taskCancellationLogic, TaskExecutionStatusLogic taskExecutionStatusLogic, ActiveMqBrokerProperties brokerProperties, JsonMapper objectMapper) {
        this.taskCancellationLogic = taskCancellationLogic;
        this.taskExecutionStatusLogic = taskExecutionStatusLogic;
        this.brokerProperties = brokerProperties;
        this.objectMapper = objectMapper;
    }

    @Async("threadPoolTaskExecutor")
    public CompletableFuture<String> sendTopic(String taskId, ActiveMQRequestDto requestDto) {
        registerTask(taskId);
        BrokerSettings broker = brokerFrom(requestDto.getActiveMQIp(), requestDto.getId(), requestDto.getPw(), requestDto.getTopic());
        try (Connection connection = createConnection(broker)) {
            connection.start();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageProducer sender = session.createProducer(session.createTopic(broker.topic()))) {
                FormatDefinition formatDefinition = FormatDefinition.from(requestDto.getFormat());
                List<String> valueRows = sortedValueRows(requestDto.getValue());
                int messageCount = resolveMessageCount(requestDto.isRepeatBoolean(), requestDto.getRepeatTime(), requestDto.getDelayTime(), requestDto.getMessageCount());
                if (valueRows.isEmpty()) {
                    sendRandomMessages(taskId, requestDto, session, sender, formatDefinition, messageCount);
                } else {
                    sendValueMessages(taskId, requestDto, session, sender, formatDefinition, valueRows, messageCount);
                }
            }
            taskExecutionStatusLogic.markSuccess(taskId);
        } catch (JMSException | JacksonException e) {
            taskExecutionStatusLogic.markFailed(taskId, e);
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskExecutionStatusLogic.markCancelled(taskId);
            throw new RuntimeException(e);
        } finally {
            taskCancellationLogic.removeTask(taskId);
        }
        return CompletableFuture.completedFuture("success");
    }

    public DryRunResponseDto dryRunTopic(ActiveMQRequestDto requestDto, int limit) throws JacksonException {
        FormatDefinition formatDefinition = FormatDefinition.from(requestDto.getFormat());
        List<String> valueRows = sortedValueRows(requestDto.getValue());
        int max = sanitizeLimit(limit);
        int repeatCount = resolveMessageCount(requestDto.isRepeatBoolean(), requestDto.getRepeatTime(), requestDto.getDelayTime(), requestDto.getMessageCount());
        int sourceRowCount = valueRows.isEmpty() ? 1 : valueRows.size();
        List<JsonNode> messages = new ArrayList<>();

        if (valueRows.isEmpty()) {
            for (int i = 0; i < repeatCount && messages.size() < max; i++) {
                messages.add(toJsonNode(createStructuredMessage(requestDto.getTcName(), formatDefinition.randomPayload())));
            }
            return createDryRunResponse(limit, repeatCount, sourceRowCount, (long) repeatCount, "RANDOM_FORMAT", messages);
        }

        for (int repeatIndex = 0; repeatIndex < repeatCount && messages.size() < max; repeatIndex++) {
            for (String valueRow : valueRows) {
                if (messages.size() >= max) break;
                messages.add(toJsonNode(createStructuredMessage(requestDto.getTcName(), formatDefinition.payloadFromValueRow(valueRow))));
            }
        }
        return createDryRunResponse(limit, repeatCount, sourceRowCount, (long) repeatCount * sourceRowCount, "VALUE_ROWS", messages);
    }

    private void sendRandomMessages(String taskId, ActiveMQRequestDto requestDto, Session session, MessageProducer sender, FormatDefinition formatDefinition, int messageCount) throws JMSException, InterruptedException, JacksonException {
        TpsPacer pacer = new TpsPacer(requestDto.getTargetTps());
        for (int i = 0; i < messageCount; i++) {
            if (isCancelled(taskId)) return;
            pacer.awaitTurn(i);
            TextMessage message = session.createTextMessage(createStructuredMessage(requestDto.getTcName(), formatDefinition.randomPayload()));
            log.info("{} random message[{}] : {}", taskId, i, SensitiveLogMasker.mask(message.getText()));
            sender.send(message);
            taskExecutionStatusLogic.incrementSentCount(taskId);
            sleep(requestDto.getDelayTime());
        }
    }

    private void sendValueMessages(String taskId, ActiveMQRequestDto requestDto, Session session, MessageProducer sender, FormatDefinition formatDefinition, List<String> valueRows, int repeatCount) throws JMSException, InterruptedException, JacksonException {
        TpsPacer pacer = new TpsPacer(requestDto.getTargetTps());
        int sentIndex = 0;
        for (int repeatIndex = 0; repeatIndex < repeatCount; repeatIndex++) {
            for (int rowIndex = 0; rowIndex < valueRows.size(); rowIndex++) {
                if (isCancelled(taskId)) return;
                pacer.awaitTurn(sentIndex++);
                Map<String, String> payload = formatDefinition.payloadFromValueRow(valueRows.get(rowIndex));
                TextMessage message = session.createTextMessage(createStructuredMessage(requestDto.getTcName(), payload));
                log.info("{} value message[repeat={}, row={}] : {}", taskId, repeatIndex, rowIndex, SensitiveLogMasker.mask(message.getText()));
                sender.send(message);
                taskExecutionStatusLogic.incrementSentCount(taskId);
                sleep(requestDto.getDelayTime());
            }
        }
    }

    @Async("threadPoolTaskExecutor")
    public CompletableFuture<String> sendFileTopic(String taskId, ActiveMQRequestFileDto requestDto) {
        registerTask(taskId);
        BrokerSettings broker = brokerFrom(requestDto.getActiveMQIp(), requestDto.getId(), requestDto.getPw(), requestDto.getTopic());
        try (Connection connection = createConnection(broker)) {
            connection.start();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageProducer sender = session.createProducer(session.createTopic(broker.topic()))) {
                Path filePath = resolveFile(requestDto.getFilePath(), requestDto.getFileName());
                String fileContents = Files.readString(filePath, StandardCharsets.UTF_8).trim();
                int messageCount = resolveMessageCount(requestDto.isRepeatBoolean(), requestDto.getRepeatTime(), requestDto.getDelayTime(), requestDto.getMessageCount());
                TpsPacer pacer = new TpsPacer(requestDto.getTargetTps());
                for (int i = 0; i < messageCount; i++) {
                    if (isCancelled(taskId)) return CompletableFuture.completedFuture("Cancelled");
                    pacer.awaitTurn(i);
                    TextMessage message = session.createTextMessage(createFileMessage(requestDto.getTcName(), taskId, fileContents));
                    log.info("{} file message[{}] : {}", taskId, i, SensitiveLogMasker.mask(message.getText()));
                    sender.send(message);
                    taskExecutionStatusLogic.incrementSentCount(taskId);
                    sleep(requestDto.getDelayTime());
                }
            }
            taskExecutionStatusLogic.markSuccess(taskId);
        } catch (JMSException | IOException | JacksonException e) {
            taskExecutionStatusLogic.markFailed(taskId, e);
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskExecutionStatusLogic.markCancelled(taskId);
            throw new RuntimeException(e);
        } finally {
            taskCancellationLogic.removeTask(taskId);
        }
        return CompletableFuture.completedFuture("success");
    }

    public DryRunResponseDto dryRunFileTopic(ActiveMQRequestFileDto requestDto, int limit) throws IOException {
        Path filePath = resolveFile(requestDto.getFilePath(), requestDto.getFileName());
        String fileContents = Files.readString(filePath, StandardCharsets.UTF_8).trim();
        int max = sanitizeLimit(limit);
        int messageCount = resolveMessageCount(requestDto.isRepeatBoolean(), requestDto.getRepeatTime(), requestDto.getDelayTime(), requestDto.getMessageCount());
        List<JsonNode> messages = new ArrayList<>();
        for (int i = 0; i < messageCount && messages.size() < max; i++) {
            messages.add(toJsonNode(createFileMessage(requestDto.getTcName(), "dry-run", fileContents)));
        }
        return createDryRunResponse(limit, messageCount, 1, messageCount, "FILE_REPEAT", messages);
    }

    @Async("threadPoolTaskExecutor")
    public CompletableFuture<String> sendFileAndDataTopic(String taskId, ActiveMQRequestFileAndDataDto requestDto) {
        registerTask(taskId);
        BrokerSettings broker = brokerFrom(requestDto.getActiveMQIp(), requestDto.getId(), requestDto.getPw(), requestDto.getTopic());
        try (Connection connection = createConnection(broker)) {
            connection.start();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageProducer sender = session.createProducer(session.createTopic(broker.topic()))) {
                Path formatFilePath = resolveFile(requestDto.getFilePath(), requestDto.getFormatFileName());
                Path dataFilePath = resolveFile(requestDto.getFilePath(), requestDto.getDataFileName());
                String originFormatContent = Files.readString(formatFilePath, StandardCharsets.UTF_8);
                List<String[]> dataLines = readDataLines(dataFilePath, originFormatContent);
                int repeatCount = resolveMessageCount(requestDto.isRepeatBoolean(), requestDto.getRepeatTime(), requestDto.getDelayTime(), requestDto.getMessageCount());
                TpsPacer pacer = new TpsPacer(requestDto.getTargetTps());
                int sentIndex = 0;
                for (int repeatIndex = 0; repeatIndex < repeatCount; repeatIndex++) {
                    for (int rowIndex = 0; rowIndex < dataLines.size(); rowIndex++) {
                        if (isCancelled(taskId)) return CompletableFuture.completedFuture("Cancelled");
                        pacer.awaitTurn(sentIndex++);
                        String renderedFormat = renderTemplate(originFormatContent, dataLines.get(rowIndex));
                        TextMessage message = session.createTextMessage(createFileDataMessage(requestDto.getTcName(), renderedFormat));
                        log.info("{} file-data message[repeat={}, row={}] : {}", taskId, repeatIndex, rowIndex, SensitiveLogMasker.mask(message.getText()));
                        sender.send(message);
                        taskExecutionStatusLogic.incrementSentCount(taskId);
                        sleep(requestDto.getDelayTime());
                    }
                }
            }
            taskExecutionStatusLogic.markSuccess(taskId);
        } catch (JMSException | IOException | JacksonException e) {
            taskExecutionStatusLogic.markFailed(taskId, e);
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskExecutionStatusLogic.markCancelled(taskId);
            throw new RuntimeException(e);
        } finally {
            taskCancellationLogic.removeTask(taskId);
        }
        return CompletableFuture.completedFuture("success");
    }

    public DryRunResponseDto dryRunFileAndDataTopic(ActiveMQRequestFileAndDataDto requestDto, int limit) throws IOException {
        Path formatFilePath = resolveFile(requestDto.getFilePath(), requestDto.getFormatFileName());
        Path dataFilePath = resolveFile(requestDto.getFilePath(), requestDto.getDataFileName());
        String originFormatContent = Files.readString(formatFilePath, StandardCharsets.UTF_8);
        List<String[]> dataLines = readDataLines(dataFilePath, originFormatContent);
        int repeatCount = resolveMessageCount(requestDto.isRepeatBoolean(), requestDto.getRepeatTime(), requestDto.getDelayTime(), requestDto.getMessageCount());
        int max = sanitizeLimit(limit);
        List<JsonNode> messages = new ArrayList<>();
        for (int repeatIndex = 0; repeatIndex < repeatCount && messages.size() < max; repeatIndex++) {
            for (String[] dataLine : dataLines) {
                if (messages.size() >= max) break;
                messages.add(toJsonNode(createFileDataMessage(requestDto.getTcName(), renderTemplate(originFormatContent, dataLine))));
            }
        }
        return createDryRunResponse(limit, repeatCount, dataLines.size(), (long) repeatCount * dataLines.size(), "FILE_DATA", messages);
    }

    private DryRunResponseDto createDryRunResponse(int requestedLimit, int repeatCount, int sourceRowCount, long estimatedTotalMessagesPerTask, String generationMode, List<JsonNode> messages) {
        return new DryRunResponseDto(Math.min(Math.max(requestedLimit, 1), 100), messages.size(), repeatCount, sourceRowCount, estimatedTotalMessagesPerTask, generationMode, messages);
    }

    private JsonNode toJsonNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Generated message is not valid JSON.", e);
        }
    }

    private void registerTask(String taskId) {
        log.info("taskId : {}", taskId);
        taskCancellationLogic.registerTask(taskId);
        taskExecutionStatusLogic.markRunning(taskId);
    }

    private Connection createConnection(BrokerSettings broker) throws JMSException {
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(broker.brokerUrl());
        return connectionFactory.createConnection(broker.username(), broker.password());
    }

    private BrokerSettings brokerFrom(String brokerUrl, String username, String password, String topic) {
        return new BrokerSettings(defaultIfBlank(brokerUrl, brokerProperties.getBrokerUrl()), defaultIfBlank(username, brokerProperties.getUsername()), defaultIfBlank(password, brokerProperties.getPassword()), defaultIfBlank(topic, brokerProperties.getTopic()));
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean isCancelled(String taskId) {
        if (taskCancellationLogic.isCancellationRequested(taskId)) {
            taskExecutionStatusLogic.markCancelled(taskId);
            log.info("작업이 취소되었습니다: {}", taskId);
            return true;
        }
        return false;
    }

    private String createStructuredMessage(String tcName, Map<String, String> payload) throws JacksonException {
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
        sb.append("\"transaction_code\":\"").append(escapeJsonString(tcName)).append("\",");
        sb.append("\"works_code\":\"K\",");
        sb.append("\"sndr_inform_edit_pgm_id\":\"\",");
        sb.append("\"eai_interface_id\":\"\",");
        sb.append("\"interface_data_dir_actual_type\":\"\",");
        sb.append("\"interface_data_ocr_res_flag\":\"\",");
        sb.append("\"interface_data_send_seq\":\"0\",");
        sb.append("\"interface_data_upd_tp\":\"\",");
        sb.append("\"interface_data_t_len\":\"760\",");
        sb.append("\"attribute\":\" \",");
        sb.append("\"bsc_gw_data_attr\":\" \",");
        sb.append("\"it_com_eai_ifc_var_item_usg_f\":\" \"");
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
            matcher.appendReplacement(result, Matcher.quoteReplacement(data[dataIndex++]));
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
        Path basePath = Paths.get(directory).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(filename).normalize();
        if (!targetPath.startsWith(basePath)) throw new IllegalArgumentException("허용되지 않은 파일 경로입니다: " + filename);
        if (!Files.isRegularFile(targetPath)) throw new IllegalArgumentException("파일을 찾을 수 없습니다: " + targetPath);
        return targetPath;
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
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public String prettyPrintUsingGlobalSetting(String uglyJsonString) throws JacksonException {
        Object jsonObject = objectMapper.readValue(uglyJsonString, Object.class);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
    }

    private record BrokerSettings(String brokerUrl, String username, String password, String topic) {
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

        private Map<String, String> randomPayload() {
            Map<String, String> payload = new LinkedHashMap<>();
            for (int i = 0; i < dataIds.size(); i++) payload.put(dataIds.get(i), RandomValueGenerator.generate(dataTypes.get(i), randomConditions.get(i)));
            return payload;
        }

        private Map<String, String> payloadFromValueRow(String row) {
            String[] values = row.split(",", -1);
            Map<String, String> payload = new LinkedHashMap<>();
            for (int i = 0; i < dataIds.size(); i++) {
                if (RandomValueGenerator.isRandomEnabled(randomBooleans.get(i))) {
                    payload.put(dataIds.get(i), RandomValueGenerator.generate(dataTypes.get(i), randomConditions.get(i)));
                    continue;
                }
                if (i >= values.length) throw new IllegalArgumentException("value 컬럼 수가 format dataId 수보다 적습니다. row=" + row);
                payload.put(dataIds.get(i), values[i].trim());
            }
            return payload;
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
