package com.dt.digitaltwinsimulator.mock;

import com.dt.digitaltwinsimulator.logic.SensitiveLogMasker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
class MockJournal {
    private static final String SECRET = "(?:authorization|proxy-authorization|cookie|set-cookie|[\\w-]*(?:password|passwd|token|secret|api[-_]?key|credential)[\\w-]*|pw)";
    private static final Pattern QUOTED_SECRET = Pattern.compile(
            "(?i)(\"" + SECRET + "\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");
    private final MockProperties properties;
    private final Deque<Entry> entries = new ArrayDeque<>();

    record Entry(Instant timestamp, String method, String path, String query, Map<String, String> headers,
                 String body, String routeId, int status, long delayMs, long elapsedMillis) { }

    MockJournal(MockProperties properties) {
        this.properties = properties;
    }

    void record(Instant timestamp, HttpServletRequest request, String path, String body,
                String routeId, int status, long delayMs, long elapsedMillis) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (headers.size() >= 32) break;
            String value = isSensitive(name) ? "****" : mask(request.getHeader(name));
            headers.put(name, truncate(value, 512));
        }
        Entry entry = new Entry(timestamp, request.getMethod(), path, truncate(mask(request.getQueryString()), 4096),
                Collections.unmodifiableMap(headers), truncate(mask(body), 4096), routeId, status, delayMs, elapsedMillis);
        synchronized (this) {
            while (entries.size() >= properties.getJournalSize()) entries.removeFirst();
            entries.addLast(entry);
        }
    }

    synchronized List<Entry> recent(int limit) {
        if (limit < 0) throw new MockInputException("limit must be >= 0");
        return entries.reversed().stream().limit(limit).toList();
    }

    synchronized void clear() {
        entries.clear();
    }

    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(?:authorization|cookie|password|passwd|token|secret|api[-_]?key|credential).*");
    }

    private static String mask(String text) {
        if (text == null) return "";
        String masked = QUOTED_SECRET.matcher(text).replaceAll("$1\"****\"");
        // Query/form and plain text secrets; JSON quoted values were handled above.
        masked = masked.replaceAll("(?i)((?:^|[?&\\s,])" + SECRET + "\\s*[:=]\\s*)[^&\\s,}]+", "$1****");
        return SensitiveLogMasker.mask(masked);
    }

    private static String truncate(String value, int bytes) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= bytes) return value;
        int end = bytes;
        while (end > 0 && (encoded[end] & 0xc0) == 0x80) end--;
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }
}
