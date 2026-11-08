package com.dt.digitaltwinsimulator.mock;

import java.util.List;
import java.util.Map;

/** Null response fields inherit the route's fields; defaults are applied after inheritance. */
public record MockRoute(String id, String method, String path, Integer status,
                        Map<String, String> headers, String body, String bodyFile,
                        Long delayMs, Long delayMinMs, Long delayMaxMs,
                        List<Response> responses, Boolean cycle, Fault fault, Boolean enabled) {
    public record Response(Integer status, Map<String, String> headers, String body, String bodyFile,
                           Long delayMs, Long delayMinMs, Long delayMaxMs) { }

    public record Fault(Double errorRate, Integer errorStatus, String errorBody) { }

    public MockRoute withEnabled(boolean value) {
        return new MockRoute(id, method, path, status, headers, body, bodyFile, delayMs,
                delayMinMs, delayMaxMs, responses, cycle, fault, value);
    }
}
