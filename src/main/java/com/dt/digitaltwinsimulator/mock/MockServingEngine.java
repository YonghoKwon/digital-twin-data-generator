package com.dt.digitaltwinsimulator.mock;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
class MockServingEngine {
    private final MockProperties properties;
    private final MockRouteStore store;
    private final MockTemplate template;
    private final MockJournal journal;
    private final JsonMapper mapper;

    MockServingEngine(MockProperties properties, MockRouteStore store, MockTemplate template,
                          MockJournal journal, JsonMapper mapper) {
        this.properties = properties;
        this.store = store;
        this.template = template;
        this.journal = journal;
        this.mapper = mapper;
    }

    ResponseEntity<byte[]> serve(HttpServletRequest request) throws IOException {
        Instant timestamp = Instant.now();
        long started = System.nanoTime();
        String path = UriUtils.decode(request.getRequestURI().substring(
                request.getContextPath().length() + properties.getBasePath().length()), StandardCharsets.UTF_8);
        if (path.isEmpty()) path = "/";
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        MockRouteStore.Match match = store.match(request.getMethod(), path);
        String routeId = match == null ? null : match.registered().route().id();
        int status = 404;
        long delay = 0;
        HttpHeaders headers = new HttpHeaders();
        String result;
        try {
            if (match == null) {
                result = mapper.writeValueAsString(Map.of("error", "NO_MOCK_ROUTE", "method", request.getMethod(), "path", path));
            } else {
                MockRoute route = match.registered().route();
                List<MockRoute.Response> responses = match.registered().responses();
                int index = Boolean.TRUE.equals(route.cycle()) ? (int) ((match.sequence() - 1) % responses.size())
                        : (int) Math.min(match.sequence() - 1, responses.size() - 1);
                MockRoute.Response response = responses.get(index);
                status = response.status();
                response.headers().forEach(headers::set);
                delay = delay(response);
                String source = store.readBody(response);
                MockRoute.Fault fault = route.fault();
                if (fault != null && fault.errorRate() != null
                        && ThreadLocalRandom.current().nextDouble() < fault.errorRate()) {
                    status = fault.errorStatus() == null ? 500 : fault.errorStatus();
                    source = fault.errorBody() == null ? "{\"error\":\"MOCK_FAULT\"}" : fault.errorBody();
                }
                String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
                boolean json = contentType == null ? source.stripLeading().startsWith("{") || source.stripLeading().startsWith("[")
                        : contentType.toLowerCase(Locale.ROOT).contains("json");
                if (contentType == null) headers.set(HttpHeaders.CONTENT_TYPE, json ? "application/json;charset=UTF-8" : "text/plain;charset=UTF-8");
                result = template.render(source, request, match.variables(), body, match.sequence(), json);
                if (delay > 0) Thread.sleep(delay);
            }
        } catch (MockInputException exception) {
            status = 400;
            headers.clear();
            result = mapper.writeValueAsString(Map.of("error", "INVALID_MOCK_ROUTE", "message", exception.getMessage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = 503;
            headers.clear();
            result = "{\"error\":\"MOCK_INTERRUPTED\"}";
        } catch (RuntimeException exception) {
            status = 500;
            headers.clear();
            result = "{\"error\":\"MOCK_RENDER_ERROR\"}";
        } finally {
            journal.record(timestamp, request, path, body, routeId, status, delay,
                    (System.nanoTime() - started) / 1_000_000);
        }
        if (!headers.containsHeader(HttpHeaders.CONTENT_TYPE)) headers.set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        return ResponseEntity.status(status).headers(headers).body(result.getBytes(StandardCharsets.UTF_8));
    }

    private long delay(MockRoute.Response response) {
        if (response.delayMs() != null) return response.delayMs();
        long min = response.delayMinMs() == null ? 0 : response.delayMinMs();
        long max = response.delayMaxMs() == null ? min : response.delayMaxMs();
        if (min == max) return min;
        if (max != Long.MAX_VALUE) return ThreadLocalRandom.current().nextLong(min, max + 1);
        return min == 0 ? ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE
                : ThreadLocalRandom.current().nextLong(min - 1, max) + 1;
    }
}
