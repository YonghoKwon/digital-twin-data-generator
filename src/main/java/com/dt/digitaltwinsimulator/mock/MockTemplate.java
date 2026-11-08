package com.dt.digitaltwinsimulator.mock;

import com.dt.digitaltwinsimulator.logic.RandomValueGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class MockTemplate {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([^{}]+)}}");
    private final JsonMapper mapper;

    MockTemplate(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String render(String template, HttpServletRequest request, Map<String, String> path,
                  String requestBody, long sequence, boolean json) {
        JsonNode body = null;
        if (!requestBody.isBlank()) {
            try {
                body = mapper.readTree(requestBody);
            } catch (RuntimeException ignored) {
                // Text requests are valid; their body.* references resolve to an empty string.
            }
        }
        StringBuilder result = new StringBuilder();
        Matcher matcher = TOKEN.matcher(template);
        int offset = 0;
        boolean inString = false;
        boolean escaped = false;
        while (matcher.find()) {
            // Track JSON string context in the source, never in substituted values.
            for (int i = offset; i < matcher.start(); i++) {
                char c = template.charAt(i);
                result.append(c);
                if (escaped) escaped = false;
                else if (inString && c == '\\') escaped = true;
                else if (c == '"') inString = !inString;
            }
            String value = resolve(matcher.group(1).trim(), request, path, body, sequence);
            if (json && inString) {
                String quoted = mapper.writeValueAsString(value);
                value = quoted.substring(1, quoted.length() - 1);
            }
            result.append(value);
            offset = matcher.end();
        }
        return result.append(template, offset, template.length()).toString();
    }

    void validate(String template) {
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            try {
                if (token.startsWith("now:")) LocalDateTime.now().format(DateTimeFormatter.ofPattern(token.substring(4)));
                if (token.startsWith("random:")) random(token);
            } catch (RuntimeException exception) {
                throw new MockInputException("Invalid template token: " + token);
            }
        }
    }

    private String resolve(String token, HttpServletRequest request, Map<String, String> path,
                           JsonNode body, long sequence) {
        if (token.startsWith("path.")) return path.getOrDefault(token.substring(5), "");
        if (token.startsWith("query.")) return emptyIfNull(request.getParameter(token.substring(6)));
        if (token.startsWith("header.")) return emptyIfNull(request.getHeader(token.substring(7)));
        if (token.startsWith("body.")) {
            JsonNode node = body;
            for (String field : token.substring(5).split("\\.")) {
                if (node == null) return "";
                node = node.get(field);
            }
            if (node == null || node.isNull()) return "";
            return node.isValueNode() ? node.asString() : mapper.writeValueAsString(node);
        }
        if (token.equals("seq")) return Long.toString(sequence);
        if (token.equals("uuid")) return UUID.randomUUID().toString();
        if (token.equals("now") || token.startsWith("now:")) {
            String pattern = token.equals("now") ? "yyyyMMddHHmmssSSS" : token.substring(4);
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
        }
        if (token.startsWith("random:")) return random(token);
        return "";
    }

    private String random(String token) {
        String[] parts = token.split(":", 3);
        if (parts.length < 2 || !parts[1].toLowerCase(java.util.Locale.ROOT)
                .matches("string|integer|int|long|double|float|number|boolean|bool|date|datetime|timestamp")) {
            throw new IllegalArgumentException("Unsupported random type");
        }
        return RandomValueGenerator.generate(parts[1], parts.length == 3 ? parts[2] : "");
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
